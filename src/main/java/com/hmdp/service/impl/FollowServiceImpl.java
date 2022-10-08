package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
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


    @Resource
    FollowMapper followMapper;

    @Resource
    ExecutorService executorService;

    @Resource
    private UserServiceImpl userService;

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {

        //1. 获取当前登录用户
        Long userId = UserHolder.getUser().getId();

        //2. 判断是关注还是取关
        if (isFollow){
            //关注
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            save(follow);
        }else {
            //取关
            remove(new QueryWrapper<Follow>().eq("user_id", userId).eq("follow_user_id", followUserId));
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {

        //获取当前用户
        Long userId = UserHolder.getUser().getId();
        int count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        return Result.ok(count > 0);
    }

    //获取共同关注
    @Override
    public Result followCommon(Long followUserId) {

        //查询被关注人的关注列表
        QueryWrapper<Follow> eq = new QueryWrapper<Follow>().select("follow_user_id").eq("user_id", followUserId);
        Set<Long> followUserIds = followMapper.selectList(eq).
                stream().map(follow -> follow.getFollowUserId()).collect(Collectors.toSet());
        Long userId = UserHolder.getUser().getId();
        //查询出当前用户的关注列表
        Set<Long> follows = query().select("follow_user_id").eq("user_id", userId)
                .list().stream().map(follow -> follow.getFollowUserId()).collect(Collectors.toSet());
        //获取它们共同关注的用户
        follows.retainAll(follows);
        if (followUserIds.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        //根据公共关注id查询用户
        List<UserDTO> userDTOS = userService.listByIds(followUserIds).stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOS);
    }
}
