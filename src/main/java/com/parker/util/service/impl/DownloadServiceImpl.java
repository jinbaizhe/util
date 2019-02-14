package com.parker.util.service.impl;

import com.alibaba.fastjson.JSON;
import com.parker.util.dao.DownloadTaskDAO;
import com.parker.util.entity.DownloadTask;
import com.parker.util.entity.DownloadTaskProcess;
import com.parker.util.entity.EnumDownloadTaskStatus;
import com.parker.util.util.DownloadUtil;
import com.parker.util.service.DownloadService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class DownloadServiceImpl implements DownloadService{
    private static final Logger logger = LoggerFactory.getLogger(DownloadServiceImpl.class);
    private ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final String saveLocation = "/opt/download/";
    private final String tempFileLocation = "/opt/tmp/";
    private final Map<String, DownloadTask> taskMap = new ConcurrentHashMap<>();
    private final Map<String, DownloadTaskProcess> taskProcessMap = new ConcurrentHashMap<>();

    @Autowired
    private DownloadTaskDAO downloadTaskDAO;

    public void download(String address, int threadsNum){
        String fileName = DownloadUtil.getFileName(address);
        download(address, saveLocation, fileName, tempFileLocation, threadsNum);
    }

    public void download(String address, String saveLocation, String fileName, String tempFileLocation, int threadsNum){
        //设置默认保存位置和缓存位置
        if (StringUtils.isBlank(saveLocation)){
            saveLocation = this.saveLocation;
        }
        if (StringUtils.isBlank(tempFileLocation)){
            tempFileLocation = this.tempFileLocation;
        }
        //创建保存和缓存目录
        File saveLocationFile = new File(saveLocation);
        File tempLocationFile = new File(tempFileLocation);
        if (!saveLocationFile.exists()){
            saveLocationFile.mkdirs();
        }
        if (!tempLocationFile.exists()){
            tempLocationFile.mkdirs();
        }
        //解决重名的问题
        fileName = DownloadUtil.getNewFileNameIfExists(saveLocation, fileName, tempFileLocation);
        address = address.trim();
        //倒计时器的数字为下载线程数+1(另外+1的是下载状态线程)
        CountDownLatch countDownLatch = new CountDownLatch(threadsNum + 1);
        long length = DownloadUtil.getFileLength(address);
        long partLength = length / threadsNum;
        //记录任务信息
        DownloadTask task = new DownloadTask();
        task.setUrl(address);
        task.setFileName(fileName);
        task.setFileSize(length);
        task.setStatus(EnumDownloadTaskStatus.NOT_STARTED.getCode());
        task.setStartTime(System.currentTimeMillis() / 1000);
        downloadTaskDAO.addDownloadTask(task);
        taskMap.put(task.getId(), task);
        //创建下载线程
        ExecutorService executorService = Executors.newFixedThreadPool(threadsNum);
        //启动显示下载状态的线程
        DownloadUtil.DownloadStatusThread downloadStatusThread = new DownloadUtil.DownloadStatusThread(task, countDownLatch);
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
                end = length -1;
            }
            String tempFileName = fileName + "#" + i;
            DownloadTaskProcess.DownloadThreadProcess threadProcess = new DownloadTaskProcess.DownloadThreadProcess();
            downloadTaskProcess.getDownloadThreadProcessMap().put(tempFileName, threadProcess);
            DownloadUtil.DownloadThread downloadThread = new DownloadUtil.DownloadThread(task, start, end, tempFileName, tempFileLocation, countDownLatch);
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
                DownloadUtil.mergeFile(threadsNum, saveLocation, fileName, tempFileLocation);
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
            if (closeCount > 3) {
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
    public void addDownloadTask(String url) {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                download(url, 3);
            }
        });
    }

    @Override
    public void addDownloadTask(String url, String fileName) {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                download(url, saveLocation, fileName, tempFileLocation, 3);
            }
        });
    }

    @Override
    public DownloadTask getDownloadTask(String taskId){
        DownloadTask downloadTask = taskMap.get(taskId);
        if (downloadTask == null){
            downloadTask = downloadTaskDAO.getDownloadTaskById(taskId);
        }
        return downloadTask;
    }

    @Override
    public List<DownloadTask> getAllCurrentDownloadTask(){
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
            task.setStatus(EnumDownloadTaskStatus.PAUSE.getCode());
            downloadTaskDAO.updateDownloadTaskStatus(task);
            return true;
        }
        return false;
    }

    @Override
    public boolean resumeDownloadTask(String taskId) {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
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
                        DownloadUtil.DownloadStatusThread downloadStatusThread = new DownloadUtil.DownloadStatusThread(task, countDownLatch);
                        Thread statusThread = new Thread(downloadStatusThread);
                        statusThread.start();
                        //建立分块任务，并分配给线程池
                        for (String tempFileName : threadProcessMap.keySet()) {
                            DownloadTaskProcess.DownloadThreadProcess threadProcess = threadProcessMap.get(tempFileName);
                            long start = threadProcess.getCurrentPosition();
                            long end = threadProcess.getEnd();
                            DownloadUtil.DownloadThread downloadThread = new DownloadUtil.DownloadThread(task, start, end, tempFileName, tempFileLocation, countDownLatch);
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
                                DownloadUtil.mergeFile(threadsNum, saveLocation, task.getFileName(), tempFileLocation);
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
                            if (closeCount > 3) {
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
}
