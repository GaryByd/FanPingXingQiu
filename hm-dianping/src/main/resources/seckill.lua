-- 参数列表
-- 优惠卷id
local voucherId = ARGV[1]
-- 用户id
local userId = ARGV[2]

-- 数据key
-- 库存key
local stockKey = 'seckill:stock:' .. voucherId
-- 订单key
local orderKey = 'seckill:order:' .. voucherId
print(stockKey)
print(orderKey)
-- 脚本业务
-- 检查库存是否充足
if(tonumber(redis.call('get', stockKey)) <= 0) then
    return 1
end

-- 检查用户是否已下单
if(redis.call('sismember', orderKey, userId) == 1) then
    return 2
end

-- 扣库存
redis.call('incrby', stockKey, -1)
-- 下单保存用户
redis.call('sadd', orderKey, userId)

return 0
