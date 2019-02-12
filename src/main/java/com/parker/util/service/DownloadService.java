package com.parker.util.service;

import com.parker.util.entity.DownloadTask;

import java.util.List;

public interface DownloadService {
    void addDownloadTask(String url);
    void addDownloadTask(String url, String fileName);
    DownloadTask getDownloadTask(String taskId);
    List<DownloadTask> getAllCurrentDownloadTask();
    boolean pauseDownloadTask(String taskId);
    boolean resumeDownloadTask(String taskId);
}
