package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

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
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Autowired
    private IUserService userService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result isFollow(Long id) {
        //1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2.查询是否关注 select count(*) from tb_follow where user_id = ? and follow_user_id = ?
        int count = query().eq("user_id", userId).eq("follow_user_id", id).count();
        return Result.ok(count > 0);
    }

    @Override
    public Result follow(Long id, Boolean isFollow) {
        //1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2.查询是否关注
        int count = query().eq("user_id", userId).eq("follow_user_id", id).count();
        if(count > 0 && isFollow == false){
            boolean success = remove(new QueryWrapper<Follow>().eq("user_id", userId).eq("follow_user_id", id));
            if(success) {
                //从redis中删除
                stringRedisTemplate.opsForSet().remove("follow:" + userId, id.toString());
            }
        }else if(count == 0 && isFollow == true){
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(id);
            boolean save = save(follow);
            if(save) {
                //存入redis
                stringRedisTemplate.opsForSet().add("follow:" + userId, id.toString());
            }
        }else{
            return Result.fail("不能重复关注或取消关注");
        }
        return Result.ok();
    }

    @Override
    public Result commonFollows(Long id) {
        //1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        String followKey = "follow:" + userId;
        //2.获取目标用户
        String followKey2 = "follow:" + id;
        //3.获取两个集合的交集
        Set<String> CommonFollow = stringRedisTemplate.opsForSet().intersect(followKey, followKey2);
        List<Long> ids;
        if (CommonFollow == null || CommonFollow.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        ids = CommonFollow.stream().map(Long::valueOf).collect(Collectors.toList());
        List<UserDTO> Users = userService.listByIds(ids).stream()
                .map(user -> {
                    UserDTO userDTO = new UserDTO();
                    BeanUtils.copyProperties(user, userDTO);
                    return userDTO;
                })
                .collect(Collectors.toList());
        return Result.ok(Users);
    }
}
