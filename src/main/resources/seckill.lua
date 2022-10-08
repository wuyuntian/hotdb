-- 优惠券id
local voucherId = ARGV[1]
--用户id
local userId = ARGV[2]
-- 订单id
local orderId = ARGV[3]

--库存key
local stockKey = "seckill:stock:" .. voucherId
--订单key
local orderKey = "seckill:order:" .. voucherId

-- 判断优惠券是否还有库存
if (tonumber(redis.call('get', stockKey)) <= 0) then
    -- 没有库存
    return 1
end
-- 判断用户是否还能下单
if (redis.call('sismember', orderKey, userId) == 1) then
    -- 用户已经下单了
    return 2
end
-- 扣除库存
redis.call('incrby', stockKey, -1)
-- 添加一个订单
redis.call('sadd', orderKey, userId)
---- 往消息队列中发送消息，等待消费者消费创建订单,XADD stream * k1 v1 k2 v2
--redis.call("xadd", "stream.orders", "*","userId", userId, "voucherId", voucherId, "id", orderId)
return 0
