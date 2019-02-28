package com.parker.util.response;

import lombok.Data;

@Data
public class AddDownloadTaskResponse {
    private String url;
    private String fileName;
    private long fileSize;
    private String message;
}
