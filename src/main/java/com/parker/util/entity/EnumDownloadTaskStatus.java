package com.parker.util.entity;

import lombok.Data;

public enum  EnumDownloadTaskStatus {
    NOT_STARTED(0, "任务未开始"),
    DOWNLOADING(1, "下载中"),
    MERGING(2, "合并文件中"),
    FINISHED(3, "任务完成"),
    FAILED(4, "下载失败");

    private Integer code;
    private String desc;

    EnumDownloadTaskStatus(Integer code, String desc){
        this.code = code;
        this.desc = desc;
    }

    public Integer getCode() {
        return code;
    }

    public String getDesc(){
        return desc;
    }
}
