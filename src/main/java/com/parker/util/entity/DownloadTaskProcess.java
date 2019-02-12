package com.parker.util.entity;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
public class DownloadTaskProcess {
    private DownloadTask task;
    private Map<String,DownloadThreadProcess> downloadThreadProcessMap = new HashMap<>();

    @Data
    public static class DownloadThreadProcess{
        private long begin;
        private long end;
        private long currentPosition;
    }
}
