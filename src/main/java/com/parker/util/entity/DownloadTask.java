package com.parker.util.entity;

import lombok.Data;

@Data
public class DownloadTask {
    private String id;
    private String url;
    private String fileName;
    private long fileSize;
    private long savedFileSize;
    private String description;
    private Integer status;
    private double currentSpeed;
    private double avgSpeed;
    private double maxSpeed;
    private long startTime;
    private long endTime;
    private long remainingTime;

}
