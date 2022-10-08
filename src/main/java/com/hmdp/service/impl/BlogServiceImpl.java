package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
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
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

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


    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;

    public  Result queryBlogById(Long id) {

        Blog blog = getById(id);
        if (blog == null){
            return Result.fail("笔记不存在");
        }

        queryBlogUser(blog);
        //查询当前博文是否已被当前用户点赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    @Override
    public Result likeBlog(Long id) {

        //1.获取登录用户
        UserDTO user = UserHolder.getUser();
        //2.查看用户是否已经点赞过了
        Double score = stringRedisTemplate.opsForZSet().score(BLOG_LIKED_KEY + id, user.getId().toString());
        if (score == null){
            //博文的点赞数增加
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            if (isSuccess){
                //更新成功，保存用户到redis的key中
                stringRedisTemplate.opsForZSet().add(BLOG_LIKED_KEY + id, user.getId().toString(), System.currentTimeMillis());
            }
        }else {
            //3.如果用户已经点赞了，博文的点赞数减一并取消当前用户在这个博文的点赞
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            if (isSuccess){
                stringRedisTemplate.opsForZSet().remove(BLOG_LIKED_KEY + id, user.getId().toString());
            }
        }
        return Result.ok();
    }

    //查询点赞过的当前博文的前五个用户
    @Override
    public Result queryBlogLikeUsers(Long id) {

        String key = BLOG_LIKED_KEY + id;
        //1.查询点赞当前过博文的前五个用户 zrange key 0 4
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 == null || top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        //解析出他们的用户id
        List<Long> userIds = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", userIds);
        //查询出用户
        //WHERE id IN ( 5 , 1 ) ORDER BY FIELD(id, 5, 1)
        List<User> users = userService.query().in("id", userIds)
                .last("ORDER BY FIELD(id,"+ idStr + ")").list();        //按照指定的顺序排序
        List<UserDTO> userDTOS = users.stream().
                map(user -> BeanUtil.copyProperties(user, UserDTO.class)).
                collect(Collectors.toList());
        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {

        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean isSuccess = save(blog);
        if (!isSuccess){
            return Result.fail("新增失败");
        }
        // 查询博客作者的所有的粉丝
         List<Follow> follows =
                followService.query().eq("follow_user_id", user.getId()).list();

        // 给所有粉丝推送消息
        for (Follow follow : follows) {
            //获取粉丝id
            Long userId = follow.getUserId();
            //推送消息，到粉丝的收件箱，每个用户都有一个收件箱
            stringRedisTemplate.opsForZSet()
                    .add(RedisConstants.FEED_KEY + userId, blog.getId().toString(), System.currentTimeMillis());
        }
        return Result.ok(blog.getId());
    }

    /**
     *
     * @param max 上一次查询的最小时间戳 初始值为当前时间戳
     * @param offset 偏移量，与上一次查询中相同的最小时间戳的个数，初始值为0
     * @return ScrollResult: List<Blog> 小于指定时间戳的blog集合</> minTIme,本次查询的最小时间戳
     * offset 偏移量
     */
    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {

        //1.查询出当前用户
        Long userId = UserHolder.getUser().getId();

        String key = FEED_KEY + userId;
        //2.查询当前用户的收件箱 REVRANGEBYSCORE key max min LIMIT offset count
        Set<ZSetOperations.TypedTuple<String>> typedTuples =
                stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        //3. 判断当前用户的收件箱是否为空
        if (typedTuples == null || typedTuples.isEmpty()){
            return Result.ok();
        }
        //4.解析数据 blogID minTime, offset
        long minTime = 0;
        int os = 1;
        List<Long> ids = new ArrayList<>();
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {

            ids.add(Long.valueOf(tuple.getValue()));
            long time = tuple.getScore().longValue();
            //判断当前时间是否是最小时间
            if (minTime == time){
                os++;       //最小时间戳个数加一
            }else { //说明集合后面还有时间戳跟小的值
                minTime = time;
                //重置偏移量
                os = 1;
            }
        }
        //5.根据id查询blog
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogList = query().in("id", ids).last("order by field(id," + idStr + ")").list();

        //6为每一个blog，查询当前用户是否点在，以及博主的信息
        for (Blog blog : blogList) {
            queryBlogUser(blog);
            isBlogLiked(blog);
        }
        //7.封装用户信息
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogList);
        scrollResult.setMinTime(minTime);
        scrollResult.setOffset(os);
        return Result.ok(scrollResult);
    }


    @Override
    public Result queryHotBlog(Integer current) {

        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog ->{
            queryBlogUser(blog);
            isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    //查询发布博文的用户
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    private void isBlogLiked(Blog blog){

        UserDTO user = UserHolder.getUser();
        if (user == null){
            //用户没有登录，直接结束
            return;
        }
        Long userId = user.getId();
        //判断博文有没有被当前用户点过赞
        Double score = stringRedisTemplate.opsForZSet().score(BLOG_LIKED_KEY + blog.getId(), userId.toString());
        blog.setIsLike(score != null);
    }


}
