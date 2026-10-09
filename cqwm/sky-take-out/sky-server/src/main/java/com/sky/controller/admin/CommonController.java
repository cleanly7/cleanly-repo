package com.sky.controller.admin;

import com.sky.utils.AliOssUtil;
import io.swagger.annotations.Api;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import com.sky.result.Result;
import io.swagger.annotations.ApiOperation;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/admin/common")
@Api(tags = "通用接口")
public class CommonController {

    @Autowired
    private AliOssUtil aliOssUtil;
    /**
     * 文件上传
     * @param file
     * @return
     */
   @PostMapping("/upload")
   @ApiOperation("文件上传")
    public Result<String> upload(MultipartFile file) throws IOException {
       try {
           log.info("文件上传：{}", file);
           String originalFilename = file.getOriginalFilename();
           String extension = originalFilename.substring(originalFilename.lastIndexOf("."));
           String fileName = UUID.randomUUID().toString() + extension;
           String url = aliOssUtil.upload(file.getBytes(), fileName);
           return Result.success(url);
       } catch (IOException e) {
           log.error("文件上传失败：{}", e);
           return Result.error("文件上传失败");
       }
   }
}
