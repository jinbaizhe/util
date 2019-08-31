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
    private ExecutorService executorService = new ThreadPoolExecutor(1, 1,
            0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<Runnable>());

    @Value("${download.save-localtion}")
    private String saveLocation;

    @Value("${download.temp-localtion}")
    private String tempLocation;

    private final Map<String, DownloadTask> taskMap = new ConcurrentHashMap<>();
    private final Map<String, DownloadTaskProcess> taskProcessMap = new ConcurrentHashMap<>();
    private final int downloadThreadNum = 3;
    private final int retryCount = 3;
    private AtomicBoolean diskIsFull = new AtomicBoolean(false);

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

    public void download(DownloadTask task, String saveLocation, String fileName, String tempFileLocation, int threadsNum){
        //设置默认保存位置和缓存位置
        if (StringUtils.isBlank(saveLocation)){
            saveLocation = this.saveLocation;
        }
        if (StringUtils.isBlank(tempFileLocation)){
            tempFileLocation = this.tempLocation;
        }
        //检查磁盘剩余可用空间
        //默认需要两倍待下载文件的大小的空间
        long needSpace = 2 * task.getFileSize();
        while ((downloadUtil.getUsableSpace(tempFileLocation) < needSpace) || (downloadUtil.getUsableSpace(saveLocation) < needSpace)){
            try {
                diskIsFull.set(true);
                long usableSpaceInTempFileLocation = downloadUtil.getUsableSpace(tempFileLocation);
                long usableSpaceInSaveLocation = downloadUtil.getUsableSpace(saveLocation);
                logger.error("磁盘可用空间不足[下载目录所在分区可用大小：" + usableSpaceInSaveLocation
                        + "B，缓存目录所在分区可用大小：" + usableSpaceInTempFileLocation + "B]，无法完成下载。");
                //等待5分钟
                Thread.sleep(5 * 60 * 1000);
            } catch (InterruptedException e) {
                logger.error(e.getMessage());
            }
        }
        diskIsFull.set(false);
        //创建保存和缓存目录
        File saveLocationFile = new File(saveLocation);
        File tempLocationFile = new File(tempFileLocation);
        if (!saveLocationFile.exists()){
            saveLocationFile.mkdirs();
        }
        if (!tempLocationFile.exists()){
            tempLocationFile.mkdirs();
        }

        //倒计时器的数字为下载线程数+1(另外+1的是下载状态线程)
        CountDownLatch countDownLatch = new CountDownLatch(threadsNum + 1);
        long partLength = task.getFileSize() / threadsNum;
        //记录任务信息
        //解决重名的问题
        fileName = downloadUtil.getNewFileNameIfExists(saveLocation, fileName, tempFileLocation);
        taskMap.put(task.getId(), task);
        //创建下载线程
        ExecutorService executorService = Executors.newFixedThreadPool(threadsNum);
        //启动显示下载状态的线程
        DownloadUtil.DownloadStatusThread downloadStatusThread = downloadUtil.new DownloadStatusThread(task, countDownLatch);
        Thread statusThread = new Thread(downloadStatusThread);
        statusThread.start();
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
            DownloadUtil.DownloadThread downloadThread = downloadUtil.new DownloadThread(task, start, end, tempFileName, tempFileLocation, countDownLatch);
            downloadThread.addObserver(downloadStatusThread);
            executorService.submit(downloadThread);
        }
        //等待下载完成
        try {
            countDownLatch.await();
            if (task.getStatus().equals(EnumDownloadTaskStatus.DOWNLOADING.getCode())) {
                task.setStatus(EnumDownloadTaskStatus.MERGING.getCode());
                //合并分块文件
                logger.debug("开始合并分块文件[任务id:" + task.getId() + "]");
                downloadUtil.mergeFile(threadsNum, saveLocation, fileName, tempFileLocation);
                logger.debug("合并分块文件完成[任务id:" + task.getId() + "]");
                task.setStatus(EnumDownloadTaskStatus.FINISHED.getCode());
                taskProcessMap.remove(task.getId());
            }
        } catch (InterruptedException e) {
            task.setStatus(EnumDownloadTaskStatus.FAILED.getCode());
            logger.error("下载文件失败[任务id:" + task.getId() + "]");
            logger.error(e.getMessage());
        }
        //清理资源，关闭线程池
        executorService.shutdown();
        int closeCount = 0;
        while (!executorService.isTerminated() || statusThread.isAlive()){
            closeCount++;
            if (closeCount > retryCount) {
                logger.info("下载线程还未全部关闭，继续等待[任务id:" + task.getId() + "]");
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        logger.debug("下载线程已全部关闭[任务id:" + task.getId() + "]");
        //设置任务状态为完成，保存至数据库中，并从TaskMap中移除
        logger.debug(JSON.toJSONString(task));
        downloadTaskDAO.updateDownloadTaskStatus(task);
        downloadTaskDAO.updateDownloadTask(task);
        taskMap.remove(task.getId());
    }

    @Override
    public DownloadTask addDownloadTask(String url, boolean isRecord) {
        String fileName = downloadUtil.getFileName(url);
        return addDownloadTask(url, fileName, isRecord);
    }

    @Override
    public DownloadTask addDownloadTask(String url, String fileName, boolean isRecord) {
        url = url.trim();
        long length = downloadUtil.getFileLength(url);
        //添加下载任务信息
        DownloadTask task = new DownloadTask();
        task.setUrl(url);
        task.setFileName(fileName);
        task.setFileSize(length);
        task.setStatus(EnumDownloadTaskStatus.NOT_STARTED.getCode());
        if (isRecord) {
            downloadTaskDAO.addDownloadTask(task);
        }
        //构造下载任务
        DownloadTaskRunnable downloadTaskRunnable = new DownloadTaskRunnable();
        downloadTaskRunnable.setTask(task);
        downloadTaskRunnable.setSaveLocation(saveLocation);
        downloadTaskRunnable.setTempFileLocation(tempLocation);
        downloadTaskRunnable.setFileName(fileName);
        downloadTaskRunnable.setThreadsNum(downloadThreadNum);
        executorService.execute(downloadTaskRunnable);
        return task;
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
        for (String taskId: taskMap.keySet()){
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
                        try {
                            countDownLatch.await();
                            if (task.getStatus().equals(EnumDownloadTaskStatus.DOWNLOADING.getCode())) {
                                task.setStatus(EnumDownloadTaskStatus.MERGING.getCode());
                                //合并分块文件
                                logger.debug("开始合并分块文件[任务id:" + task.getId() + "]");
                                downloadUtil.mergeFile(threadsNum, saveLocation, task.getFileName(), tempLocation);
                                logger.debug("合并分块文件完成[任务id:" + task.getId() + "]");
                                task.setStatus(EnumDownloadTaskStatus.FINISHED.getCode());
                                taskProcessMap.remove(task.getId());
                            }
                        } catch (InterruptedException e) {
                            task.setStatus(EnumDownloadTaskStatus.FAILED.getCode());
                            logger.error("下载文件失败[任务id:" + task.getId() + "]");
                            logger.error(e.getMessage());
                        }
                        //清理资源，关闭线程池
                        executorService.shutdown();
                        int closeCount = 0;
                        while (!executorService.isTerminated() || statusThread.isAlive()) {
                            closeCount++;
                            if (closeCount > retryCount) {
                                logger.info("下载线程还未全部关闭，继续等待[任务id:" + task.getId() + "]");
                            }
                            try {
                                Thread.sleep(100);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        }
                        logger.debug("下载线程已全部关闭[任务id:" + task.getId() + "]");
                        //设置任务状态为完成，保存至数据库中，并从TaskMap中移除
                        downloadTaskDAO.updateDownloadTaskStatus(task);
                        downloadTaskDAO.updateDownloadTask(task);
                        taskMap.remove(task.getId());
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
