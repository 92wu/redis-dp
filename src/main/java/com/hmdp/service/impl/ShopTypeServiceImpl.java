package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryList() {
        String key = CACHE_TYPE_KEY;
        //从redis中查看商铺类型业务
        String typeJson = stringRedisTemplate.opsForValue().get(key);
        //判断是否存在
        if (StrUtil.isNotBlank(typeJson)) {
            //存在，返回商铺类型列表
            List<ShopType> shopTypes = JSONUtil.toList(typeJson, ShopType.class);
            return Result.ok(shopTypes);
        }
        //不存在，查询数据库-->列表集合
        List<ShopType> shopTypeList = query().orderByAsc("sort").list();
        //判断集合是否存在
        if (shopTypeList == null) {
            //不存在，返回错误
            return Result.fail("分类不存在");
        }
        //存在，将商铺类型写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shopTypeList), CACHE_TYPE_TTL, TimeUnit.MINUTES);
        //返回
        return Result.ok(shopTypeList);
    }
}
