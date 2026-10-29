package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Autowired
    private IFollowService followService;
    @Autowired
    private IUserService userService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryBlogById(Long id) {
        //1.查blog
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("博客不存在");
        }
        //2.查blog相关user
        User user = userService.getById(blog.getUserId());
        //3.封装 user 名称和头像
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
        //4.判断是否点赞
        isLikedBlog(blog.getId());

        return Result.ok(blog);
    }

    private void isLikedBlog(Long id) {
        Blog blog = getById(id);
        Long userId = UserHolder.getUser().getId();
        Double score = stringRedisTemplate.opsForZSet().score("blog:liked", userId.toString());
        blog.setIsLike(score != null);
    }

    @Override
    public Result likeBlog(Long id) {
        //1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2.判断是否点赞
        Double score = stringRedisTemplate.opsForZSet().score("blog:liked", userId.toString());
        if (score == null) {
            //3.如果没赞,可以点赞
            //3.1 点赞数 + 1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            //3.2 redis 存储点赞关系
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().add("blog:liked", userId.toString(), System.currentTimeMillis());
            }
        } else {
            //4.如果已赞,取消点赞
            //4.1 点赞数 - 1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            //4.2 redis 删除点赞关系
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().remove("blog:liked", userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryHotBlog(Integer current) {
        //判断当前用户是否已登录
        UserDTO userNow = UserHolder.getUser();
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            Long userId = blog.getUserId();
            User user = userService.getById(userId);
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
            //4.判断是否点赞
            if(userNow != null){
                isLikedBlog(blog.getId());
            }
        });
        return Result.ok(records);
    }

    @Override
    public Result saveBlog(Blog blog) {
        //1.获取当前用户id
        Long userId = UserHolder.getUser().getId();
        blog.setUserId(userId);
        //2.保存blog
        boolean save = save(blog);
        //3.查询粉丝
        List<Long> fansId;
        if(save){
            List<Follow> follows = followService.query().eq("follow_user_id", userId).list();
            fansId = follows.stream().map(Follow::getUserId).collect(Collectors.toList());
        }else{
            return Result.fail("保存失败");
        }
        for (Long fanId : fansId) {
            String key = "feed:" + fanId + ": inbox";
            //推送blog ID 到 粉丝 的收件箱
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogByFollow(long max, Integer offset) {
        //1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2.获取用户inbox的 message ID
        String key = "feed:" + userId + ": inbox";
        Set<ZSetOperations.TypedTuple<String>> typedTuples =
                stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, 0, max, offset, 3);
        //非空
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }
        //3.获取blog: blogs , minTime ,offset
        List<Long> ids = new ArrayList<>();
        long minTime = 0L;
        int os = 1;
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples){
            //3.1获取ID
            Long blogId = Long.valueOf(tuple.getValue());
            //3.2获取时间戳
            long timestamp = tuple.getScore().longValue();
            ids.add(blogId);
            if(timestamp == minTime){
                os++;
            }else{
                minTime = timestamp;
                os = 1;
            }
        }
        List<Blog> blogs = query()
                .in("id", ids)
                .last("ORDER BY FIELD(id, " + StrUtil.join(",", ids) + ")")
                .list();
        for (Blog blog : blogs) {
            //判断是否点赞
            isLikedBlog(blog.getId());
            //查blog相关user
            User user = userService.getById(blog.getUserId());
            //封装 user 名称和头像
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
        }
        //4.封装scollResult
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setMinTime(minTime);
        scrollResult.setOffset(os);
        return Result.ok(scrollResult);
    }

    @Override
    public Result queryBlogLikes(Long id) {
        Long userId = UserHolder.getUser().getId();
        if (userId == null){
            return null;
        }
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("博客不存在");
        }
        Set<String> userIds = stringRedisTemplate.opsForZSet().range("blog:liked", 0, 4);
        if (userIds != null) {
            List<Long> userIdList = userIds.stream().map(Long::valueOf).collect(Collectors.toList());
            List<User> users = userService.query()
                    .in("id", userIdList)
                    .last("ORDER BY FIELD(id, " + StrUtil.join(",", userIdList) + ")")
                    .list();
            List<UserDTO> userDTOs = users.stream().map(user -> {
                UserDTO userDTO = new UserDTO();
                BeanUtils.copyProperties(user, userDTO);
                return userDTO;
            }).collect(Collectors.toList());
            return Result.ok(userDTOs);
        } else {
            return Result.ok(Collections.emptyList());
        }


    }
}
