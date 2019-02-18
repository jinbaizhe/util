package com.parker.util.dao;

import com.parker.util.entity.DownloadTask;

public interface DownloadTaskDAO {
    void addDownloadTask(DownloadTask downloadTask);
    void updateDownloadTask(DownloadTask downloadTask);
    void updateDownloadTaskStatus(DownloadTask downloadTask);
    void deleteDownloadTask(String id);
    DownloadTask getDownloadTaskById(String id);
}
