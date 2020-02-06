package com.parker.util.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.parker.util.constant.CommonConstant;
import com.parker.util.dao.DownloadTaskDAO;
import com.parker.util.entity.DownloadTask;
import com.parker.util.entity.DownloadTaskProcess;
import com.parker.util.enums.EnumDownloadTaskStatus;
import com.parker.util.util.DownloadUtil;
import com.parker.util.service.DownloadService;
import com.parker.util.util.RedisManager;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class DownloadServiceImpl implements DownloadService{
    private static final Logger logger = LoggerFactory.getLogger(DownloadServiceImpl.class);

    private ExecutorService executorService = new ThreadPoolExecutor(1, 2,
            0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<Runnable>());

    @Value("${download.save-localtion}")
    private String saveLocation;

    @Value("${download.temp-localtion}")
    private String tempLocation;

    private final Map<Integer, DownloadTask> taskMap = new ConcurrentHashMap<>();
    private final Map<Integer, DownloadTaskProcess> taskProcessMap = new ConcurrentHashMap<>();
    private final int downloadThreadNum = 3;
    private final int retryCount = 10;
    private AtomicBoolean diskIsFull = new AtomicBoolean(false);

    private static final long CHECK_INTERVAL = 5 * 60 * 1000;

    @Value("${download.redis-db-index}")
    private int dbIndex;

    @Autowired
    private DownloadTaskDAO downloadTaskDAO;

    @Autowired
    private DownloadUtil downloadUtil;

    @Autowired
    private RedisManager redisManager;

    public void download(DownloadTask task, int threadsNum){
        download(task, saveLocation, task.getFileName(), tempLocation, threadsNum);
    }

    public void download(DownloadTask task, String saveLocation, String fileName, String tempLocation, int threadsNum){
        //设置文件保存位置和缓存位置
        String sLocation = StringUtils.isBlank(saveLocation) ? this.saveLocation : saveLocation;
        String tLocation = StringUtils.isBlank(tempLocation) ? this.tempLocation : tempLocation;

        //检查磁盘剩余可用空间
        checkDisk(task, sLocation, tLocation);

        //倒计时器的数字为下载线程数+1(另外+1的是下载状态线程)
        CountDownLatch countDownLatch = new CountDownLatch(threadsNum + 1);
        long partLength = task.getFileSize() / threadsNum;

        //记录任务信息
        //解决重名的问题
        fileName = downloadUtil.getNewFileNameIfExists(sLocation, fileName, tLocation);

        //记录当前任务
        taskMap.put(task.getId(), task);

        //创建用于下载的线程池
        ExecutorService executorService = Executors.newFixedThreadPool(threadsNum);

        //启动记录下载状态的线程
        DownloadUtil.DownloadStatusThread downloadStatusThread = downloadUtil.new DownloadStatusThread(task, countDownLatch);
        Thread statusThread = new Thread(downloadStatusThread);
        statusThread.start();

        //开始下载
        startDownload(task, fileName, threadsNum, tLocation, countDownLatch, partLength, executorService, downloadStatusThread);

        //等待下载完成，并合并分块文件
        waitAndMerge(task, fileName, threadsNum, sLocation, tLocation, countDownLatch);

        //清理资源，关闭线程池
        clearExecutor(task, executorService, statusThread);

        //设置任务状态为完成，保存任务信息至数据库中，并从TaskMap中移除
        finishTask(task);
    }

    /**
     * 开始下载
     * @param task
     * @param fileName
     * @param threadsNum
     * @param tLocation
     * @param countDownLatch
     * @param partLength
     * @param executorService
     * @param downloadStatusThread
     */
    private void startDownload(DownloadTask task, String fileName, int threadsNum, String tLocation
            , CountDownLatch countDownLatch, long partLength, ExecutorService executorService
            , DownloadUtil.DownloadStatusThread downloadStatusThread) {
        DownloadTaskProcess downloadTaskProcess = new DownloadTaskProcess();
        downloadTaskProcess.setTask(task);
        taskProcessMap.put(task.getId(), downloadTaskProcess);

        //建立分块任务，并分配给线程池
        for (int i=0;i<threadsNum;i++){
            long start = i * partLength;
            long end = start + partLength -1;
            if (i == (threadsNum -1)){
                end = task.getFileSize() -1;
            }
            String tempFileName = fileName + "#" + i;
            DownloadTaskProcess.DownloadThreadProcess threadProcess = new DownloadTaskProcess.DownloadThreadProcess();
            downloadTaskProcess.getDownloadThreadProcessMap().put(tempFileName, threadProcess);
            DownloadUtil.DownloadThread downloadThread = downloadUtil.new DownloadThread(task, start, end, tempFileName, tLocation, countDownLatch);
            downloadThread.addObserver(downloadStatusThread);
            executorService.submit(downloadThread);
        }
    }

    /**
     * 检查磁盘剩余可用空间
     * @param task
     * @param sLocation
     * @param tLocation
     */
    private void checkDisk(DownloadTask task, String sLocation, String tLocation) {
        //默认需要两倍待下载文件的大小的空间
        //所有缓存文件合并成完整文件时需要两倍的空间大小
        long needSpace = 2 * task.getFileSize();
        while (diskIsFull.get() || !downloadUtil.isHaveEnoughSpace(tLocation,needSpace) || !downloadUtil.isHaveEnoughSpace(sLocation, needSpace)){
            try {
                diskIsFull.set(true);
                long usableSpaceInTempFileLocation = downloadUtil.getUsableSpace(tLocation);
                long usableSpaceInSaveLocation = downloadUtil.getUsableSpace(sLocation);
                logger.error("磁盘可用空间不足，无法完成下载[下载目录所在分区可用大小=[{}]，缓存目录所在分区可用大小=[{}]",
                        DownloadUtil.getFileSizeString(usableSpaceInSaveLocation),
                        DownloadUtil.getFileSizeString(usableSpaceInTempFileLocation));
                //等待5分钟
                Thread.sleep(CHECK_INTERVAL);
            } catch (InterruptedException e) {
                logger.error("检查磁盘空间大小时发生错误：[{}]", e);
            }
        }
        diskIsFull.set(false);

        //创建保存目录
        File saveLocationFile = new File(sLocation);
        if (!saveLocationFile.exists()){
            saveLocationFile.mkdirs();
        }

        //创建缓存目录
        File tempLocationFile = new File(tLocation);
        if (!tempLocationFile.exists()){
            tempLocationFile.mkdirs();
        }
    }

    /**
     * 设置任务状态为完成，保存任务信息至数据库中，并从TaskMap中移除
     * @param task
     */
    private void finishTask(DownloadTask task) {
        downloadTaskDAO.updateDownloadTaskStatus(task);
        downloadTaskDAO.updateDownloadTask(task);
        taskMap.remove(task.getId());
    }

    /**
     * 清理资源，关闭线程池
     * @param task
     * @param executorService
     * @param statusThread
     */
    private void clearExecutor(DownloadTask task, ExecutorService executorService, Thread statusThread) {
        executorService.shutdown();
        int closeCount = 0;
        while (!executorService.isTerminated() || statusThread.isAlive()){
            closeCount++;
            if (closeCount > retryCount) {
                logger.info("下载线程池还未全部关闭，继续等待：task=[{}]", JSON.toJSONString(task));
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                logger.error("关闭线程池时暂停失败：exception=[{}]", JSON.toJSONString(task), e);
            }
        }
        logger.info("下载线程池已关闭：task=[{}]", JSON.toJSONString(task));
    }

    /**
     * 等待下载完成，并合并分块文件
     * @param task
     * @param fileName
     * @param threadsNum
     * @param sLocation
     * @param tLocation
     * @param countDownLatch
     */
    private void waitAndMerge(DownloadTask task, String fileName, int threadsNum, String sLocation, String tLocation, CountDownLatch countDownLatch) {
        try {
            countDownLatch.await();
            if (task.getStatus().equals(EnumDownloadTaskStatus.DOWNLOADING.getCode())) {
                task.setStatus(EnumDownloadTaskStatus.MERGING.getCode());
                //合并分块文件
                logger.info("开始合并分块文件：task=[{}]", JSON.toJSONString(task));
                downloadUtil.mergeFile(threadsNum, sLocation, fileName, tLocation);
                logger.info("合并分块文件完成：task=[{}]", JSON.toJSONString(task));
                task.setStatus(EnumDownloadTaskStatus.FINISHED.getCode());
                taskProcessMap.remove(task.getId());
            } else {
                logger.error("下载失败：任务状态不正确, task=[{}]", JSON.toJSONString(task));
            }
        } catch (InterruptedException e) {
            task.setStatus(EnumDownloadTaskStatus.FAILED.getCode());
            logger.error("下载文件失败：task=[{}]，exception=[{}]", JSON.toJSONString(task), e);
        }
    }

    @Override
    public void addDownloadTask(DownloadTask downloadTask) {
        String url = downloadTask.getUrl();
        String fileName = downloadTask.getFileName();
        if (StringUtils.isEmpty(fileName)) {
            fileName = downloadUtil.getFileName(url);
        }
        url = url.trim();
        //获取文件大小
        long length = downloadUtil.getFileLength(downloadTask.getUrl());

        //添加下载任务信息
        DownloadTask task = new DownloadTask();
        BeanUtils.copyProperties(downloadTask, task);
        task.setUrl(url);
        task.setFileName(fileName);
        task.setFileSize(length);
        task.setSavedFileSize(0);
        task.setStatus(EnumDownloadTaskStatus.NOT_STARTED.getCode());
        downloadTaskDAO.addDownloadTask(task);

        //构造下载任务
        DownloadTaskRunnable downloadTaskRunnable = new DownloadTaskRunnable();
        downloadTaskRunnable.setTask(task);
        downloadTaskRunnable.setSaveLocation(saveLocation);
        downloadTaskRunnable.setTempFileLocation(tempLocation);
        downloadTaskRunnable.setFileName(task.getFileName());
        downloadTaskRunnable.setThreadsNum(downloadThreadNum);
        executorService.execute(downloadTaskRunnable);
    }


    @Override
    public DownloadTask getDownloadTask(String taskId){
        DownloadTask downloadTask = taskMap.get(taskId);
        if (downloadTask == null){
            String key = CommonConstant.DOWNLOAD_TASK + CommonConstant.REDIS_KEY_SEPARATOR + taskId;
            downloadTask = JSONObject.parseObject(redisManager.get(dbIndex, key), DownloadTask.class);
        }
        if (downloadTask == null){
            downloadTask = downloadTaskDAO.getDownloadTaskById(taskId);
        }
        return downloadTask;
    }

    @Override
    public List<DownloadTask> getAllCurrentDownloadTaskList(){
        List<DownloadTask> list = new LinkedList();
        for (Integer taskId: taskMap.keySet()){
            list.add(taskMap.get(taskId));
        }
        return list;
    }

    @Override
    public boolean pauseDownloadTask(String taskId) {
        DownloadTask task = getDownloadTask(taskId);
        if (task == null){
            return false;
        }
        if (task.getStatus().equals(EnumDownloadTaskStatus.DOWNLOADING.getCode())) {
            // TODO: 2019/2/28 还需要执行暂停操作，中断下载线程
            task.setStatus(EnumDownloadTaskStatus.PAUSE.getCode());
            //保存信息到Redis
            String key = CommonConstant.DOWNLOAD_TASK + CommonConstant.REDIS_KEY_SEPARATOR + task.getId();
            redisManager.set(dbIndex, key, JSON.toJSONString(task));
            downloadTaskDAO.updateDownloadTaskStatus(task);
            return true;
        }
        return false;
    }

    @Override
    public boolean resumeDownloadTask(String taskId) {
        // TODO: 2019/2/28 还需判断任务是否已经在执行
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                // TODO: 2019/2/28 优化：减少该部分的冗余代码
                DownloadTask task = getDownloadTask(taskId);
                if (task != null && task.getStatus().equals(EnumDownloadTaskStatus.PAUSE.getCode())) {
                    task.setStatus(EnumDownloadTaskStatus.DOWNLOADING.getCode());
                    downloadTaskDAO.updateDownloadTaskStatus(task);
                    //获得原先任务下载信息
                    DownloadTaskProcess taskProcess = taskProcessMap.get(task.getId());
                    Map<String, DownloadTaskProcess.DownloadThreadProcess> threadProcessMap = null;
                    int threadsNum = 0;
                    if (taskProcess != null) {
                        threadProcessMap = taskProcess.getDownloadThreadProcessMap();
                        threadsNum = threadProcessMap.size();
                        CountDownLatch countDownLatch = new CountDownLatch(threadsNum);
                        //创建下载线程
                        ExecutorService executorService = Executors.newFixedThreadPool(threadsNum);
                        //启动显示下载状态的线程
                        DownloadUtil.DownloadStatusThread downloadStatusThread = downloadUtil.new DownloadStatusThread(task, countDownLatch);
                        Thread statusThread = new Thread(downloadStatusThread);
                        statusThread.start();
                        //建立分块任务，并分配给线程池
                        for (String tempFileName : threadProcessMap.keySet()) {
                            DownloadTaskProcess.DownloadThreadProcess threadProcess = threadProcessMap.get(tempFileName);
                            long start = threadProcess.getCurrentPosition();
                            long end = threadProcess.getEnd();
                            DownloadUtil.DownloadThread downloadThread = downloadUtil.new DownloadThread(task, start, end, tempFileName, tempLocation, countDownLatch);
                            downloadThread.addObserver(downloadStatusThread);
                            executorService.submit(downloadThread);
                        }

                        //等待下载完成
                        waitAndMerge(task, task.getFileName(), threadsNum, saveLocation, tempLocation, countDownLatch);

                        //清理资源，关闭线程池
                        clearExecutor(task, executorService, statusThread);

                        //设置任务状态为完成，保存任务信息至数据库中，并从TaskMap中移除
                        finishTask(task);
                    }
                }
            }
        });
        return true;
    }

    @Override
    public List<DownloadTask> getAllUnfinishedDownloadTaskList() {
        List list = new LinkedList();
        list.addAll(getAllCurrentDownloadTaskList());
        list.addAll(getAllWaitingTaskList());
        return list;
    }

    @Override
    public List<DownloadTask> getAllWaitingTaskList(){
        LinkedList list = new LinkedList();
        ThreadPoolExecutor executor = (ThreadPoolExecutor)executorService;
        Iterator iterator = executor.getQueue().iterator();
        DownloadTaskRunnable downloadTaskRunnable = null;
        while (iterator.hasNext()){
            downloadTaskRunnable = (DownloadTaskRunnable) iterator.next();
            list.add(downloadTaskRunnable.getTask());
        }
        return list;
    }

    @Override
    public boolean isDiskFull(){
        return diskIsFull.get();
    }

    @Data
    public class DownloadTaskRunnable implements Runnable{
        private DownloadTask task;
        private String fileName;
        private String saveLocation;
        private String tempFileLocation;
        private int threadsNum;
        @Override
        public void run() {
            download(task, saveLocation, fileName, tempFileLocation, threadsNum);
        }
    }
}
