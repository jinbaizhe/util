package com.parker.util.service;

import com.parker.util.entity.DownloadTask;

import java.util.List;

public interface DownloadService {

    void addDownloadTask(DownloadTask downloadTask);

    DownloadTask getDownloadTask(String taskId);

    List<DownloadTask> getAllCurrentDownloadTaskList();

    boolean pauseDownloadTask(String taskId);

    boolean resumeDownloadTask(String taskId);

    List<DownloadTask> getAllUnfinishedDownloadTaskList();

    List<DownloadTask> getAllWaitingTaskList();

    boolean isDiskFull();
}
