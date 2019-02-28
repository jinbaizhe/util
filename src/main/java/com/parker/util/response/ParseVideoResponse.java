package com.parker.util.response;

import lombok.Data;

@Data
public class ParseVideoResponse {
    private String url;
    private String description;
    private Integer id;
    private String message;
}
