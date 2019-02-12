package com.parker.util.util;

import com.alibaba.fastjson.JSONObject;
import com.parker.util.entity.Video;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ParseVideoUtil {
    private static final Logger logger = LoggerFactory.getLogger(ParseVideoUtil.class);
    private static final Pattern urlPattern = Pattern.compile("\"url\":\"([^\"]*)\",");
    private static final Pattern descPattern = Pattern.compile("\"desc\":\"([^\"]*)\"");
    private static final Pattern hashPattern = Pattern.compile("var\\s*hash\\s*=\\s*\"([^\"]*)\";", Pattern.CASE_INSENSITIVE);
    private static final String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/?.?.?.? Safari/537.36";

    private static HttpPost getApiHttpPost(String url, String hashValue){
        HttpPost httpPost = new HttpPost("https://www.parsevideo.com/api.php");
        //构造请求头
        httpPost.setHeader("accept", "text/javascript, application/javascript, application/ecmascript, application/x-ecmascript, */*; q=0.01");
        //加入下面一行会乱码
        //原因：这个头信息是告诉服务器客户端所支持的压缩方式。
        //如果没有这行的话，就是告诉服务器，客户端不支持压缩，要求不压缩直接返回文本。
//        httpPost.setHeader("accept-encoding", "gzip, deflate, br");
        httpPost.setHeader("accept-language", "zh-CN,zh;q=0.9");
        httpPost.setHeader("content-type", "application/x-www-form-urlencoded; charset=UTF-8");
        httpPost.setHeader("origin", "https://www.parsevideo.com");
        httpPost.setHeader("user-agent", getRandomUserAgent());
        httpPost.setHeader("x-requested-with", "XMLHttpRequest");
        //构造请求参数
        List<NameValuePair> nameValuePairList = new ArrayList<>();
        nameValuePairList.add(new BasicNameValuePair("url", url));
        nameValuePairList.add(new BasicNameValuePair("hash", hashValue));
        try {
            httpPost.setEntity(new UrlEncodedFormEntity(nameValuePairList));
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        return httpPost;
    }

    public static Video parseVideoURL(String url){
        if (StringUtils.isBlank(url)){
            return null;
        }
        CloseableHttpClient httpClient = HttpClients.createDefault();
        String hashValue = getHashValue(httpClient);
        HttpPost httpPost = getApiHttpPost(url, hashValue);
        Video video = null;
        try {
            CloseableHttpResponse response = httpClient.execute(httpPost);
            video = parseResponse(response);
            response.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return video;
    }

    private static String getHashValue(CloseableHttpClient httpClient){
        HttpGet httpGet = new HttpGet("https://www.parsevideo.com");
        httpGet.setHeader("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/71.0.3578.98 Safari/537.36");

        String result = null;
        String hashValue = null;
        try {
            CloseableHttpResponse response = httpClient.execute(httpGet);
            HttpEntity entity = response.getEntity();
            result = EntityUtils.toString(entity, "utf-8");
        } catch (IOException e) {
            e.printStackTrace();
        }

        //解析Response
        Matcher matcher = hashPattern.matcher(result);
        if (matcher.find()){
            hashValue = matcher.group(1);
        }
        return hashValue;
    }

    private static Video parseResponse(CloseableHttpResponse response){
        Video video = null;
        HttpEntity entity = response.getEntity();
        String content = null;
        try {
            content = EntityUtils.toString(entity, "utf-8");
            logger.info(content);
            EntityUtils.consume(entity);
        } catch (IOException e) {
            logger.error(content);
            e.printStackTrace();
        }
        int beginIndex = content.indexOf('{');
        int endIndex = content.lastIndexOf('}');
        String jsonString = content.substring(beginIndex, endIndex+1);
        VideoParseResponse videoParseResponse = JSONObject.parseObject(jsonString, VideoParseResponse.class);
        if (videoParseResponse != null && videoParseResponse.getVideo() != null && videoParseResponse.getVideo().get(0) != null){
            video = new Video();
            video.setDesc(videoParseResponse.getVideo().get(0).getDesc());
            video.setUrl(videoParseResponse.getVideo().get(0).getUrl());
        }else if ((videoParseResponse != null) && (videoParseResponse.getStatus() != null) && videoParseResponse.getStatus().equals("error")){
            logger.error("地址输入错误，无法解析");
        }else if (videoParseResponse != null &&  (videoParseResponse.getCaptcha() != null) && videoParseResponse.getCaptcha().equals("ok")){
            logger.error("需要手动输入验证码！");
        } else {
            //此时可能要求输入验证码
            logger.error("解析时遇到未知错误");
        }
        logger.debug("response content:" + content);
        logger.debug("json string:" + jsonString);
        return video;
    }

    private static String getRandomUserAgent(){
        String s = userAgent;
        Random random = new Random();
        while (s.indexOf('?') != -1){
            s = s.replaceFirst("\\?", random.nextInt(1000)+"");
        }
        return s;
    }

    @Data
    private static class VideoParseResponse{
        private Integer page;
        private Integer total;
        private List<VideoParseResponseInfo> video;
        private String status;
        private String captcha;

        @Data
        static class VideoParseResponseInfo{
            private String url;
            private String thumb;
            private String desc;
        }
    }
}
