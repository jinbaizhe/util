package com.parker.util.dao;

import com.parker.util.entity.DownloadTask;
import org.springframework.stereotype.Repository;

@Repository
public interface DownloadTaskDAO {
    /**
     *
     * @param downloadTask
     */
    void addDownloadTask(DownloadTask downloadTask);

    /**
     *
     * @param downloadTask
     */
    void updateDownloadTask(DownloadTask downloadTask);

    /**
     *
     * @param downloadTask
     */
    void updateDownloadTaskStatus(DownloadTask downloadTask);

    /**
     *
     * @param id
     */
    void deleteDownloadTask(String id);

    /**
     *
     * @param id
     * @return
     */
    DownloadTask getDownloadTaskById(String id);
}
