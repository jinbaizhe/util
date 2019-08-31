package com.parker.util.util;

import com.alibaba.fastjson.JSON;
import com.parker.util.constant.CommonConstant;
import com.parker.util.entity.DownloadTask;
import com.parker.util.entity.DownloadTaskProcess;
import com.parker.util.enums.EnumDownloadTaskStatus;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.net.*;
import java.util.Map;
import java.util.Observable;
import java.util.Observer;
import java.util.concurrent.CountDownLatch;

@Component
public class DownloadUtil {
    private static final Logger logger = LoggerFactory.getLogger(DownloadUtil.class);

    @Value("${download.redis-db-index}")
    private int dbIndex;

    @Autowired
    private RedisManager redisManager;

    public String getFileName(String address){
        int index = address.lastIndexOf('/');
        if (index != -1){
            address = address.substring(index + 1);
            index = address.lastIndexOf('?');
            if (index != -1){
                address = address.substring(0, index);
            }
            return address;
        }
        return "unname";
    }

    public String doubleToStringWithFormat(double d){
        return String.format("%.2f", d);
    }

    public boolean isValidURL(String address){
        HttpURLConnection connection = null;
        connection = (HttpURLConnection)getURLConnection(address);
        connection.setReadTimeout(5000);
        int responseCode = -1;
        try {
            responseCode = connection.getResponseCode();
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (responseCode == 200){
            return true;
        }
        return false;
    }

    public URLConnection getURLConnection(String address){
        if (StringUtils.isBlank(address)){
            return null;
        }
        URLConnection connection = null;
        try {
            URL url = new URL(address);
            connection = url.openConnection();
            connection.setRequestProperty("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/71.0.3578.98 Safari/537.36");
            connection.setReadTimeout(2000);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return connection;
    }

    public long getFileLength(String address){
        int count = 2;
        long length = 0 ;
        while (count > 0 && (length <= 0)){
            URLConnection connection = getURLConnection(address);
            length = connection.getContentLengthLong();
            count--;
        }
        return length;
    }

    public void mergeFile(int partsNum, String saveLocation, String fileName, String tempFileLocation){
        long startTime = System.currentTimeMillis();
        double fileSize = 0;
        try {
            RandomAccessFile file = new RandomAccessFile(tempFileLocation + fileName + "#0", "rwd");
            byte[] bytes = new byte[8192];
            for (int i=1;i<partsNum;i++){
                long startPosition = file.length();
                file.seek(startPosition);
                String tempFileName = tempFileLocation + fileName + "#" + i;
                File tempFile = new File(tempFileName);
                InputStream inputStream = new FileInputStream(tempFile);
                BufferedInputStream bufferedInputStream = new BufferedInputStream(inputStream);
                int length = 0;
                while ((length=(bufferedInputStream.read(bytes))) != -1){
                    file.write(bytes,0, length);
                }
                if (bufferedInputStream != null){
                    bufferedInputStream.close();
                }
                if (inputStream != null){
                    inputStream.close();
                }
                if (tempFile !=null && tempFile.exists()){
                    boolean isDelete = tempFile.delete();
                    if (!isDelete){
                        logger.error("缓存文件["+ tempFileName + "]删除失败");
                    }
                }
            }
            if (file != null) {
                fileSize = file.length()/1.0/1024/1024;
                file.close();
            }
            File totalTempFile = new File(tempFileLocation + fileName + "#0");
            if (totalTempFile.exists()){
                totalTempFile.renameTo(new File(saveLocation + fileName));
            }else {
                logger.error("缓存文件丢失，需重新提交下载任务");
            }
        } catch (FileNotFoundException e) {
            logger.error(e.getMessage());
        }catch (IOException e){
            logger.error(e.getMessage());
        }
        long endTime = System.currentTimeMillis();
        double spendTime = (endTime - startTime) / 1.0 / 1000;
        double speed = fileSize/spendTime;
        logger.debug("合并文件完成[文件名：" + fileName + "，总大小：" + doubleToStringWithFormat(fileSize) + "MB，耗时：" + doubleToStringWithFormat(spendTime) + "秒,速度：" + doubleToStringWithFormat(speed) + "MB/s]");
    }

    public String getNewFileNameIfExists(String saveLocation, String fileName, String tempFileLocation){
        File savefile = new File(saveLocation + fileName);
        File tempfile = new File(tempFileLocation + fileName);
        while (savefile.exists() || tempfile.exists()){
            fileName = "(1)" + fileName;
            savefile = new File(saveLocation + fileName);
            tempfile = new File(tempFileLocation + fileName);
        }
        return fileName;
    }

    public long getTotalSpace(String filePath){
        File file = new File(filePath);
        return file.getTotalSpace();
    }

    public long getUsableSpace(String filePath){
        File file = new File(filePath);
        return file.getUsableSpace();
    }

    public long getUsedSpace(String filePath){
        File file = new File(filePath);
        return file.getTotalSpace() - file.getUsableSpace();
    }

    @Data
    public class DownloadThread extends Observable implements Runnable{
        private DownloadTask task;
        private long start;
        private long end;
        private String tempFileName;
        private String tempFileLocation;
        private CountDownLatch countDownLatch;
        public DownloadThread(DownloadTask task, long start, long end, String tempFileName,String tempFileLocation, CountDownLatch countDownLatch){
            this.task = task;
            this.start = start;
            this.end = end;
            this.tempFileName = tempFileName;
            this.tempFileLocation = tempFileLocation;
            this.countDownLatch = countDownLatch;
        }

        @Override
        public void run() {
            URLConnection connection = null;
            String rangeValue = "bytes=" + start + "-" + end;
            byte[] bytes = new byte[8192];
            RandomAccessFile file = null;
            InputStream inputStream = null;
            BufferedInputStream bufferedInputStream = null;
            int count = 0;
            while (true) {
                if (count >= 3){
                    logger.error("下载失败[文件名:"+ tempFileName + "]");
                    task.setStatus(EnumDownloadTaskStatus.FAILED.getCode());
                    countDownLatch.countDown();
                    break;
                }
                try {
                    connection = getURLConnection(task.getUrl());
                    connection.setRequestProperty("Range", rangeValue);
                    connection.setReadTimeout(5000);
                    file = new RandomAccessFile(tempFileLocation + tempFileName, "rwd");
                    inputStream = connection.getInputStream();
                    bufferedInputStream = new BufferedInputStream(inputStream);
                    int length = 0;
                    while (!(task.getStatus().equals(EnumDownloadTaskStatus.PAUSE.getCode())) && ((length = (bufferedInputStream.read(bytes))) != -1)) {
                        file.write(bytes, 0, length);
                        setChanged();
                        notifyObservers(length);
                    }
                    countDownLatch.countDown();
                    break;
                } catch (IOException e) {
                    logger.error(e.toString());
                    try {
                        Thread.sleep(10000);
                    } catch (InterruptedException e1) {
                        logger.error(e1.getMessage());
                    }
                }finally {
                    count++;
                    try {
                        if (bufferedInputStream != null) {
                            bufferedInputStream.close();
                        }
                        if (inputStream != null) {
                            inputStream.close();
                        }
                        if (file != null) {
                            file.close();
                        }
                    }catch (IOException e){
                        logger.error(e.getMessage());
                    }
                }
            }
        }
    }

    @Data
    public class DownloadStatusThread implements Runnable,Observer {
        private long fileSize;
        private long tempFileSize;
        private DownloadTask task;
        private DownloadTaskProcess downloadTaskProcess;
        private Map downloadThreadProcessMap;
        private CountDownLatch countDownLatch;

        public DownloadStatusThread(DownloadTask task, CountDownLatch countDownLatch){
            this.task = task;
            this.fileSize = task.getFileSize();
            tempFileSize = 0;
            this.countDownLatch = countDownLatch;
            downloadTaskProcess = new DownloadTaskProcess();
            downloadThreadProcessMap = downloadTaskProcess.getDownloadThreadProcessMap();
        }

        @Override
        public void run() {
            //设置任务状态为开始下载状态
            task.setStatus(EnumDownloadTaskStatus.DOWNLOADING.getCode());
            long startTime = System.currentTimeMillis();
            task.setStartTime(startTime);
            long lastTempFileLength = tempFileSize;
            double currentSpeedKB = 0;
            double maxSpeedKB = 0;
            double remainTime = 0;
            //取样时间(ms)
            int timeDelta = 500;
            long fileSizeDelta = 0;
            while ((countDownLatch.getCount() > 1) && tempFileSize<fileSize){
                fileSizeDelta = tempFileSize - lastTempFileLength;
                if (fileSizeDelta ==0 ){
                    continue;
                }
                currentSpeedKB = fileSizeDelta /1.0 / 1024 / (timeDelta / 1.0 / 1000);
                lastTempFileLength = tempFileSize;
                maxSpeedKB = (maxSpeedKB < currentSpeedKB)? currentSpeedKB : maxSpeedKB;
                //计算剩余时间
                remainTime = (fileSize - tempFileSize) / 1.0 / 1024 / currentSpeedKB;
                //保存相关的下载信息
                task.setSavedFileSize(tempFileSize);
                task.setMaxSpeed(Math.round(maxSpeedKB));
                task.setCurrentSpeed(Math.round(currentSpeedKB));
                task.setRemainingTime(Math.round(remainTime));
                String key = CommonConstant.DOWNLOAD_TASK + CommonConstant.REDIS_KEY_SEPARATOR + task.getId();
                redisManager.set(dbIndex, key, JSON.toJSONString(task));
                try {
                    Thread.sleep(timeDelta);
                } catch (InterruptedException e) {
                    countDownLatch.countDown();
                    logger.error("下载状态线程被中断[任务id:" + task.getId() + "]");
                }
            }
            //当记录的已下载大小大于文件本身大小时
            task.setSavedFileSize(tempFileSize > task.getFileSize() ? tempFileSize : task.getFileSize());
            if (task.getStatus().equals(EnumDownloadTaskStatus.DOWNLOADING.getCode())) {
                long endTime = System.currentTimeMillis();
                double spendTime = endTime - startTime;
                double fileSizeKB = fileSize / 1.0 / 1024;
                double fileSizeMB = fileSizeKB / 1024;
                double avgSpeedKB = Math.round(fileSizeKB / (spendTime / 1000));
                double avgSpeedMB = avgSpeedKB / 1024;
                logger.debug("下载文件完成[文件名：" + task.getFileName() + "，总大小：" + doubleToStringWithFormat(fileSizeMB) + "MB，耗时：" + doubleToStringWithFormat(spendTime / 1000) + "秒，平均下载速度：" + doubleToStringWithFormat(avgSpeedMB) + "MB/s]");
                task.setStartTime(startTime / 1000);
                task.setEndTime(endTime / 1000);
                task.setMaxSpeed(maxSpeedKB > avgSpeedKB ? maxSpeedKB : avgSpeedKB);
                task.setAvgSpeed(avgSpeedKB);
                task.setCurrentSpeed(0);
                task.setRemainingTime(0);
                task.setSavedFileSize(tempFileSize);
            }
            //移除Redis中的记录
            String key = CommonConstant.DOWNLOAD_TASK + CommonConstant.REDIS_KEY_SEPARATOR + task.getId();
            redisManager.delMatchKey(0, key);
            countDownLatch.countDown();
            logger.info("文件下载成功：文件名：{}， 平均下载速度：{}", task.getFileName(), task.getAvgSpeed());
        }

        @Override
        public void update(Observable o, Object arg) {
            synchronized (DownloadStatusThread.class) {
                long temp = Long.parseLong(arg.toString());
                tempFileSize += temp;
                DownloadThread downloadThread = (DownloadThread) o;
                DownloadTaskProcess.DownloadThreadProcess downloadThreadProcess = (DownloadTaskProcess.DownloadThreadProcess) downloadThreadProcessMap.get(downloadThread.tempFileName);
                if (downloadThreadProcess != null) {
                    downloadThreadProcess.setEnd(downloadThreadProcess.getCurrentPosition() + temp);
                }else {
                    downloadThreadProcess = new DownloadTaskProcess.DownloadThreadProcess();
                    downloadThreadProcess.setBegin(downloadThread.getStart());
                    downloadThreadProcess.setEnd(downloadThread.getEnd());
                    downloadThreadProcess.setCurrentPosition(downloadThread.getStart() + temp);
                    downloadThreadProcessMap.put(downloadThread.tempFileName, downloadThreadProcess);
                }
            }
        }
    }
}
