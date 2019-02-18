package com.parker.util.controller;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.parker.util.entity.AddDownloadTaskResponse;
import com.parker.util.entity.DownloadTask;
import com.parker.util.entity.ParseVideoResponse;
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
        ParseVideoResponse response = new ParseVideoResponse();
        Video video = ParseVideoUtil.parseVideoURL(url);
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
                           @RequestParam(value = "fileName",required = false) String fileName){
        AddDownloadTaskResponse response = new AddDownloadTaskResponse();
        String headers = JSON.toJSONString(map);
        logger.info(headers);
        if (StringUtils.isBlank(url)){
            response.setMessage("地址输入有误");
        } else if (downloadService.isDiskFull()){
            response.setMessage("服务器磁盘空间已满，任务提交失败");
        } else {
            DownloadTask task = null;
            if (StringUtils.isBlank(fileName)) {
                task = downloadService.addDownloadTask(url);
            } else {
                task = downloadService.addDownloadTask(url, fileName);
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
        AddDownloadTaskResponse response = new AddDownloadTaskResponse();
        String headers = JSON.toJSONString(map);
        logger.info(headers);
        if (StringUtils.isBlank(url)){
            response.setMessage("地址输入有误");
            return JSON.toJSONString(response);
        }
        url = url.trim();
        Video video = ParseVideoUtil.parseVideoURL(url);
        if (video==null || StringUtils.isBlank(video.getUrl())){
            response.setMessage("解析视频地址时出错");
            return JSON.toJSONString(response);
        }
        String fileName = video.getDesc().replace("/","");
        return download(map, video.getUrl(), fileName);
    }

    @RequestMapping("/getDownloadTask")
    @ResponseBody
    public String getDownloadTask(@RequestHeader HashMap map, @RequestParam("taskId") String taskId){
        DownloadTask downloadTask = downloadService.getDownloadTask(taskId);
        return JSON.toJSONString(downloadTask);
    }

    @RequestMapping("/getAllCurrentDownloadTask")
    @ResponseBody
    public String getAllCurrentDownloadTask(@RequestHeader HashMap map){
        List<DownloadTask> taskList = downloadService.getAllCurrentDownloadTaskList();
        return JSONArray.toJSONString(taskList);
    }

    @RequestMapping("/getAllUnfinishedDownloadTask")
    @ResponseBody
    public String getAllUnfinishedDownloadTask(@RequestHeader HashMap map){
        List<DownloadTask> taskList = downloadService.getAllUnfinishedDownloadTaskList();
        return JSONArray.toJSONString(taskList);
    }

    @RequestMapping("/getAllWaitingTaskList")
    @ResponseBody
    public String getAllWaitingTaskList(@RequestHeader HashMap map){
        List<DownloadTask> taskList = downloadService.getAllWaitingTaskList();
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
