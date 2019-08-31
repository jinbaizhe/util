package com.parker.util.controller;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.parker.util.response.AddDownloadTaskResponse;
import com.parker.util.entity.DownloadTask;
import com.parker.util.response.ParseVideoResponse;
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
import java.util.Map;

@Controller
@RequestMapping("/util")
public class UtilController {
    private Logger logger = LoggerFactory.getLogger(UtilController.class);

    @Autowired
    private DownloadService downloadService;

    @Autowired
    private ParseVideoUtil parseVideoUtil;

    @RequestMapping("/parseVideo")
    @ResponseBody
    public String parseVideo(@RequestHeader HashMap map, @RequestParam("url") String url){
        logHeader(map);
        ParseVideoResponse response = new ParseVideoResponse();
        Video video = parseVideoUtil.parseVideoURL(url);
        if (video==null || StringUtils.isBlank(video.getUrl())){
            response.setMessage("解析视频地址时出错");
        }else {
            response.setUrl(video.getUrl());
            response.setId(video.getId());
            response.setDescription(video.getDesc());
            response.setMessage("解析视频地址成功");
        }
        String result = JSON.toJSONString(response);
        logger.info(result);
        return result;
    }

    @RequestMapping("/download")
    @ResponseBody
    public String download(@RequestHeader HashMap map, @RequestParam("url") String url,
                           @RequestParam(value = "fileName",required = false) String fileName, Boolean isRecord){
        logHeader(map);
        AddDownloadTaskResponse response = new AddDownloadTaskResponse();
        if (StringUtils.isBlank(url)){
            response.setMessage("地址输入有误");
        } else if (downloadService.isDiskFull()){
            response.setMessage("服务器磁盘空间已满，任务提交失败");
        } else {
            DownloadTask task = null;
            if (StringUtils.isBlank(fileName)) {
                task = downloadService.addDownloadTask(url, isRecord);
            } else {
                task = downloadService.addDownloadTask(url, fileName, isRecord);
            }
            response.setMessage("提交下载任务成功");
            response.setUrl(task.getUrl());
            response.setFileName(task.getFileName());
            response.setFileSize(task.getFileSize());
        }
        return JSON.toJSONString(response);
    }

    @RequestMapping("/parseVideoAndDownload")
    @ResponseBody
    public String parseVideoAndDownload(@RequestHeader HashMap map, @RequestParam("url") String url){
        logHeader(map);
        AddDownloadTaskResponse response = new AddDownloadTaskResponse();
        if (StringUtils.isBlank(url)){
            response.setMessage("地址输入有误");
            return JSON.toJSONString(response);
        }
        url = url.trim();
        Video video = parseVideoUtil.parseVideoURL(url);
        if (video==null || StringUtils.isBlank(video.getUrl())){
            response.setMessage("解析视频地址时出错");
            return JSON.toJSONString(response);
        }
        String fileName = video.getDesc().replace("/","") + ".mp4";
        return download(map, video.getUrl(), fileName, true);
    }

    @RequestMapping("/getDownloadTask")
    @ResponseBody
    public String getDownloadTask(@RequestHeader HashMap map, @RequestParam("taskId") String taskId){
        logHeader(map);
        DownloadTask downloadTask = downloadService.getDownloadTask(taskId);
        return JSON.toJSONString(downloadTask);
    }

    @RequestMapping("/getAllCurrentDownloadTask")
    @ResponseBody
    public String getAllCurrentDownloadTask(@RequestHeader HashMap map){
        logHeader(map);
        List<DownloadTask> taskList = downloadService.getAllCurrentDownloadTaskList();
        return JSONArray.toJSONString(taskList);
    }

    @RequestMapping("/getAllUnfinishedDownloadTask")
    @ResponseBody
    public String getAllUnfinishedDownloadTask(@RequestHeader HashMap map){
        logHeader(map);
        List<DownloadTask> taskList = downloadService.getAllUnfinishedDownloadTaskList();
        return JSONArray.toJSONString(taskList);
    }

    @RequestMapping("/getAllWaitingTaskList")
    @ResponseBody
    public String getAllWaitingTaskList(@RequestHeader HashMap map){
        logHeader(map);
        List<DownloadTask> taskList = downloadService.getAllWaitingTaskList();
        return JSONArray.toJSONString(taskList);
    }

    @RequestMapping("/pauseDownloadTask")
    @ResponseBody
    public String pauseDownloadTask(@RequestHeader HashMap map, @RequestParam("taskId") String taskId){
        logHeader(map);
        downloadService.pauseDownloadTask(taskId);
        DownloadTask task = downloadService.getDownloadTask(taskId);
        return JSON.toJSONString(task);
    }

    @RequestMapping("/resumeDownloadTask")
    @ResponseBody
    public String resumeDownloadTask(@RequestHeader HashMap map, @RequestParam("taskId") String taskId){
        logHeader(map);
        downloadService.resumeDownloadTask(taskId);
        DownloadTask task = downloadService.getDownloadTask(taskId);
        return JSON.toJSONString(task);
    }

    @RequestMapping("/downloadByRecord")
    @ResponseBody
    public String downloadByRecord(@RequestHeader HashMap map,
                                   @RequestParam(value = "taskId", required = false) String taskId,
                                   @RequestParam(value = "startIndex", required = false) String startIndex,
                                   @RequestParam(value = "endIndex", required = false) String endIndex){
        logHeader(map);
        if (taskId != null){
            DownloadTask task = downloadService.getDownloadTask(taskId);
            download(map, task.getUrl(), task.getFileName(), false);
        }
        return "提交成功";
    }

    private void logHeader(Map map){
        String headers = JSON.toJSONString(map);
        logger.info(headers);
    }
}
