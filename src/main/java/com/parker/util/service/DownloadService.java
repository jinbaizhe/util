package com.parker.util.service;

import com.parker.util.entity.DownloadTask;

import java.util.List;

public interface DownloadService {
    DownloadTask addDownloadTask(String url, boolean isRecord);
    DownloadTask addDownloadTask(String url, String fileName, boolean isRecord);
    DownloadTask getDownloadTask(String taskId);
    List<DownloadTask> getAllCurrentDownloadTaskList();
    boolean pauseDownloadTask(String taskId);
    boolean resumeDownloadTask(String taskId);
    List<DownloadTask> getAllUnfinishedDownloadTaskList();
    List<DownloadTask> getAllWaitingTaskList();
    boolean isDiskFull();
}
