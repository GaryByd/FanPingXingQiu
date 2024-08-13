-- 获取锁的线程标志
local id = redis.call('get',KEYS[1])
-- 与当前线程标识进行比较
-- 比较线程标志与锁种的标志是否一致
if(id == ARGV[1]) then
    redis.call('del',KEYS[1])
end
return 0