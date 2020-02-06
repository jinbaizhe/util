package com.parker.util.entity;

import lombok.Data;

import java.util.Date;

@Data
public class DownloadTask {

    private Integer id;

    private String url;

    private String fileName;

    private long fileSize;

    private long savedFileSize;

    private String description;

    private Integer status;

    private double currentSpeed;

    private double avgSpeed;

    private double maxSpeed;

    private Date startTime;

    private Date endTime;

    private long remainingTime;

    private String remark;
}
