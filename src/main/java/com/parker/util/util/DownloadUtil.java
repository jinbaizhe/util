package com.parker.util.util;

import com.parker.util.dao.DownloadTaskDAO;
import com.parker.util.entity.DownloadTask;
import com.parker.util.entity.EnumDownloadTaskStatus;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.*;
import java.net.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Observable;
import java.util.Observer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DownloadUtil {
    private static final Logger logger = LoggerFactory.getLogger(DownloadUtil.class);

    public static String getFileName(String address){
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

    public static String doubleToStringWithFormat(double d){
        return String.format("%.2f", d);
    }

    public static boolean isValidURL(String address){
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

    public static URLConnection getURLConnection(String address){
        if (StringUtils.isBlank(address)){
            return null;
        }
        URLConnection connection = null;
        try {
            URL url = new URL(address);
            connection = url.openConnection();
            connection.setRequestProperty("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/71.0.3578.98 Safari/537.36");
        } catch (Exception e) {
            e.printStackTrace();
        }
        return connection;
    }

    public static long getFileLength(String address){
        URLConnection connection = getURLConnection(address);
        long length = connection.getContentLengthLong();
        return length;
    }

    public static void mergeFile(int partsNum,long partLength, String saveLocation, String fileName, String tempFileLocation){
        long startTime = System.currentTimeMillis();
        double fileSize = 0;
        try {
            RandomAccessFile file = new RandomAccessFile(saveLocation + fileName, "rwd");
            byte[] bytes = new byte[8192];
            for (int i=0;i<partsNum;i++){
                long startPosition = i * partLength;
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
                        System.out.println("缓存文件["+ tempFileName + "]删除失败");
                    }
                }
            }
            if (file != null) {
                fileSize = file.length()/1.0/1000/1000;
                file.close();
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }catch (IOException e){
            e.printStackTrace();
        }
        long endTime = System.currentTimeMillis();
        double spendTime = (endTime - startTime)/1000;
        double speed = fileSize/spendTime;
        logger.info("合并文件完成[文件名：" + fileName + "，总大小：" + doubleToStringWithFormat(fileSize) + "MB，耗时：" + doubleToStringWithFormat(spendTime) + "秒,速度：" + doubleToStringWithFormat(speed) + "MB/s]");
    }

    public static String getNewFileNameIfExists(String saveLocation, String fileName, String tempFileLocation){
        File savefile = new File(saveLocation + fileName);
        File tempfile = new File(tempFileLocation + fileName);
        while (savefile.exists() || tempfile.exists()){
            fileName = "(1)" + fileName;
            savefile = new File(saveLocation + fileName);
            tempfile = new File(tempFileLocation + fileName);
        }
        return fileName;
    }

    public static class DownloadThread extends Observable implements Runnable{
        private String address;
        private long start;
        private long end;
        private String fileName;
        private String tempFileLocation;
        private CountDownLatch countDownLatch;
        public DownloadThread(String address, long start, long end, String fileName, String tempFileLocation, CountDownLatch countDownLatch){
            this.address = address;
            this.start = start;
            this.end = end;
            this.fileName = fileName;
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
                    logger.error("下载失败[文件名:"+ fileName + "]");
                    break;
                }
                try {
                    connection = getURLConnection(address);
                    connection.setRequestProperty("Range", rangeValue);
                    connection.setReadTimeout(5000);
                    file = new RandomAccessFile(tempFileLocation + fileName, "rwd");
                    inputStream = connection.getInputStream();
                    bufferedInputStream = new BufferedInputStream(inputStream);
                    int length = 0;
                    while ((length = (bufferedInputStream.read(bytes))) != -1) {
                        setChanged();
                        notifyObservers(length);
                        file.write(bytes, 0, length);
                    }
                    countDownLatch.countDown();
                    break;
                } catch (IOException e) {
                    logger.error(e.toString());
                    try {
                        Thread.sleep(10000);
                    } catch (InterruptedException e1) {
                        e1.printStackTrace();
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
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    public static class DownloadStatusThread implements Runnable,Observer {
        private long fileLength;
        private long tempFileLength;
        private DownloadTask task;

        public DownloadStatusThread(DownloadTask task, long fileLength){
            this.fileLength = fileLength;
            this.task = task;
        }

        @Override
        public void run() {
            //设置任务状态为开始下载状态
            task.setStatus(EnumDownloadTaskStatus.DOWNLOADING.getCode());
            long startTime = System.currentTimeMillis() / 1000;
            long lastTempFileLength = tempFileLength;
            double currentSpeedKB = 0;
            double maxSpeedKB = 0;
            double remainTime = 0;
            while (tempFileLength<fileLength){
                currentSpeedKB = (tempFileLength - lastTempFileLength) / 1000 / 0.5;
                lastTempFileLength = tempFileLength;
                maxSpeedKB = (maxSpeedKB < currentSpeedKB)? currentSpeedKB : maxSpeedKB;
                //计算剩余时间
                remainTime = (fileLength - tempFileLength) / 1000 / currentSpeedKB;
                //保存相关的下载信息
                task.setSavedFileSize(tempFileLength);
                task.setMaxSpeed(maxSpeedKB);
                task.setCurrentSpeed(currentSpeedKB);
                task.setRemainingTime(Math.round(remainTime));
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            long endTime = System.currentTimeMillis() / 1000;
            double spendTime = endTime - startTime;
            double fileSizeKB = fileLength / 1.0 / 1000;
            double fileSizeMB = fileSizeKB / 1000;
            double avgSpeedKB = fileSizeKB / spendTime;
            double avgSpeedMB = fileSizeMB / spendTime;
            logger.info("下载文件完成[文件名：" + task.getFileName() + "，总大小：" + doubleToStringWithFormat(fileSizeMB) + "MB，耗时：" + doubleToStringWithFormat(spendTime) + "秒，平均下载速度：" + doubleToStringWithFormat(avgSpeedMB) +  "MB/s]");
            task.setStartTime(startTime);
            task.setEndTime(endTime);
            task.setMaxSpeed(maxSpeedKB);
            task.setAvgSpeed(avgSpeedKB);
            task.setSavedFileSize(tempFileLength);
        }

        @Override
        public void update(Observable o, Object arg) {
            synchronized (DownloadStatusThread.class) {
                long temp = Long.parseLong(arg.toString());
                tempFileLength += temp;
            }
        }
    }

}
