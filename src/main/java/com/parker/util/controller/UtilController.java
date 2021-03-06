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
                           @RequestParam(value = "fileName",required = false) String fileName){
        logHeader(map);
        AddDownloadTaskResponse response = new AddDownloadTaskResponse();
        if (StringUtils.isBlank(url)){
            response.setMessage("地址输入有误");
        } else if (downloadService.isDiskFull()){
            response.setMessage("服务器磁盘空间已满，任务提交失败");
        } else {
            DownloadTask task = new DownloadTask();
            task.setUrl(url);
            task.setFileName(fileName);
            task.setDescription("");
            task.setRemark("");
            downloadService.addDownloadTask(task);
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
        } else if (downloadService.isDiskFull()){
            response.setMessage("服务器磁盘空间已满，任务提交失败");
            return JSON.toJSONString(response);
        }

        //获得解析后的地址
        Video video = parseVideoUtil.parseVideoURL(url);
        if (video==null || StringUtils.isBlank(video.getUrl())){
            response.setMessage("解析视频地址时出错");
            return JSON.toJSONString(response);
        }

        //拼接文件名
        //取描述字段作为文件名，默认后缀为.mp4
        String suffix = ".mp4";
        int suffixIndex = video.getUrl().lastIndexOf(".");
        if (suffixIndex != -1) {
            suffix = video.getUrl().substring(suffixIndex);
        }
        String fileName = video.getDesc().replace("/","") + suffix;

        //提交下载任务
        DownloadTask downloadTask = new DownloadTask();
        downloadTask.setUrl(video.getUrl());
        downloadTask.setFileName(fileName);
        downloadTask.setDescription(video.getDesc());
        downloadTask.setRemark(url.trim());

        downloadService.addDownloadTask(downloadTask);
        response.setUrl(downloadTask.getUrl());
        response.setFileName(downloadTask.getFileName());

        return JSON.toJSONString(response);
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
                                   @RequestParam(value = "startIndex", required = false) Integer startIndex,
                                   @RequestParam(value = "endIndex", required = false) Integer endIndex){
        logHeader(map);
        if (taskId != null){
            DownloadTask task = downloadService.getDownloadTask(taskId);
            download(map, task.getUrl(), task.getFileName());
            return "提交成功";
        } else if (startIndex != null && endIndex != null && startIndex <= endIndex) {
            for (int i = startIndex; i<= endIndex; i++) {
                DownloadTask task = downloadService.getDownloadTask(i + "");
                if (task == null){
                    logger.error("查找不到对应的任务记录：taskId={}", i);
                    continue;
                }
                downloadService.addDownloadTask(task);
            }
            return "提交成功";
        }
        return "请求参数错误";
    }

    private void logHeader(Map map){
        String headers = JSON.toJSONString(map);
        logger.info(headers);
    }
}
