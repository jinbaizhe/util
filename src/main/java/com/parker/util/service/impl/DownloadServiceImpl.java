package com.parker.util.service.impl;

import com.parker.util.dao.DownloadTaskDAO;
import com.parker.util.entity.DownloadTask;
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
    private static final Map<String, DownloadTask> taskMap = new ConcurrentHashMap<>();

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
        CountDownLatch countDownLatch = new CountDownLatch(threadsNum);
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
        DownloadUtil.DownloadStatusThread downloadStatusThread = new DownloadUtil.DownloadStatusThread(task, length);
        Thread statusThread = new Thread(downloadStatusThread);
        statusThread.start();
        //建立分块任务，并分配给线程池
        for (int i=0;i<threadsNum;i++){
            long start = i * partLength;
            long end = start + partLength -1;
            if (i == (threadsNum -1)){
                end = length -1;
            }
            DownloadUtil.DownloadThread downloadThread = new DownloadUtil.DownloadThread(address, start, end, fileName + "#" + i, tempFileLocation, countDownLatch);
            downloadThread.addObserver(downloadStatusThread);
            executorService.submit(downloadThread);
        }
        //等待下载完成
        try {
            countDownLatch.await();
            task.setStatus(EnumDownloadTaskStatus.MERGING.getCode());
        } catch (InterruptedException e) {
            task.setStatus(EnumDownloadTaskStatus.FAILED.getCode());
            e.printStackTrace();
        }
        //合并分块文件
        DownloadUtil.mergeFile(threadsNum, partLength, saveLocation, fileName, tempFileLocation);
        //清理资源，关闭线程池
        executorService.shutdown();
        while (!executorService.isTerminated() || statusThread.isAlive()){
            try {
                Thread.sleep(500);
                logger.warn("下载线程还未全部关闭，继续等待");
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        logger.info("下载线程已全部关闭");
        //设置任务状态为完成，保存至数据库中，并从TaskMap中移除
        task.setStatus(EnumDownloadTaskStatus.FINISHED.getCode());
        downloadTaskDAO.updateDownloadTaskStatus(EnumDownloadTaskStatus.FINISHED.getCode());
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

}
