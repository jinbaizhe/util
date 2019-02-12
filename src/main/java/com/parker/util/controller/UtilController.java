package com.parker.util.controller;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.parker.util.entity.DownloadTask;
import com.parker.util.util.ParseVideoUtil;
import com.parker.util.service.DownloadService;
import com.parker.util.entity.Video;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;

@Controller
@RequestMapping("/util")
public class UtilController {
    private Logger logger = LoggerFactory.getLogger(UtilController.class);

    @Autowired
    private DownloadService downloadService;

    @RequestMapping("/parseVideo")
    @ResponseBody
    public String parseVideo(@RequestHeader HashMap map, @RequestParam("url") String url){
        String headers = JSON.toJSONString(map);
        logger.info(headers);
        Video video = ParseVideoUtil.parseVideoURL(url);
        logger.info(video.getUrl());
        return video.getUrl();
    }

    @RequestMapping("/download")
    @ResponseBody
    public String download(@RequestHeader HashMap map, @RequestParam("url") String url){
        String headers = JSON.toJSONString(map);
        logger.info(headers);
        if (StringUtils.isBlank(url)){
            return "地址输入有误";
        }
        downloadService.addDownloadTask(url);
        return "下载任务提交成功";
    }

    @RequestMapping("/parseVideoAndDownload")
    @ResponseBody
    public String parseVideoAndDownload(@RequestHeader HashMap map, @RequestParam("url") String url){
        String headers = JSON.toJSONString(map);
        logger.info(headers);
        if (StringUtils.isBlank(url)){
            return "地址输入有误";
        }
        url = url.trim();
        Video video = ParseVideoUtil.parseVideoURL(url);
        if (video==null || StringUtils.isBlank(video.getUrl())){
            return "解析视频地址时出错";
        }
        String fileName = video.getDesc().replace("/","");
        downloadService.addDownloadTask(video.getUrl(), fileName + ".mp4");
        return "下载任务提交成功";
    }

    @RequestMapping("/getDownloadTask")
    @ResponseBody
    public String getDownloadTask(@RequestHeader HashMap map, @RequestParam("taskId") String taskId){
        DownloadTask downloadTask = downloadService.getDownloadTask(taskId);
        return JSON.toJSONString(downloadTask);
    }

    @RequestMapping("/getAllCurrentDownloadTask")
    @ResponseBody
    public String getDownloadTask(@RequestHeader HashMap map){
        List<DownloadTask> taskList = downloadService.getAllCurrentDownloadTask();
        return JSONArray.toJSONString(taskList);
    }

    @RequestMapping("/pauseDownloadTask")
    @ResponseBody
    public String pauseDownloadTask(@RequestHeader HashMap map, @RequestParam("taskId") String taskId){
        boolean result = downloadService.pauseDownloadTask(taskId);
        String s = "暂停任务失败";
        if (result){
            s = "暂停任务成功";
        }
        return s;
    }

    @RequestMapping("/resumeDownloadTask")
    @ResponseBody
    public String resumeDownloadTask(@RequestHeader HashMap map, @RequestParam("taskId") String taskId){
        boolean result = downloadService.resumeDownloadTask(taskId);
        String s = "恢复任务失败";
        if (result){
            s = "恢复任务成功";
        }
        return s;
    }
}
