package com.snowcattle.game.db.service.proxy;

import com.snowcattle.game.db.cache.redis.RedisInterface;
import com.snowcattle.game.db.cache.redis.RedisListInterface;
import com.snowcattle.game.db.cache.redis.RedisService;
import com.snowcattle.game.db.common.Loggers;
import com.snowcattle.game.db.common.annotation.DbOperation;
import com.snowcattle.game.db.common.enums.DbOperationEnum;
import com.snowcattle.game.db.entity.AbstractEntity;
import com.snowcattle.game.db.entity.IEntity;
import com.snowcattle.game.db.service.entity.EntityService;
import com.snowcattle.game.db.util.EntityUtils;
import com.snowcattle.game.db.util.ObjectUtils;
import org.slf4j.Logger;
import org.springframework.cglib.proxy.MethodInterceptor;
import org.springframework.cglib.proxy.MethodProxy;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Created by jwp on 2017/3/23.
 * 实体存储服务代理 同步存储
 *
 * 存储策略为 insert的时候插入db,然后更新缓存。query的时候优先缓存，找不到的时候查询db,更新缓存。delete的时候删除db，删除缓存，
 * useRedisFlag 为是否使用缓存redis标志
 */
public class EntityServiceProxy<T extends EntityService>  implements MethodInterceptor {

    private static final Logger proxyLogger = Loggers.dbServiceProxy;

    private RedisService redisService;

    private boolean useRedisFlag;

    public EntityServiceProxy(RedisService redisService, boolean useRedisFlag) {
        this.redisService = redisService;
        this.useRedisFlag = useRedisFlag;
    }

    @Override
    public Object intercept(Object obj, Method method, Object[] args, MethodProxy methodProxy) throws Throwable {
        Object result = null;
        DbOperation dbOperation = method.getAnnotation(DbOperation.class);
        if(dbOperation == null || !useRedisFlag) { //如果没有进行注解或者不使用redis，直接进行返回
            result = methodProxy.invokeSuper(obj, args);
        }else {
            //进行数据库操作
            DbOperationEnum dbOperationEnum = dbOperation.operation();
            switch (dbOperationEnum) {
                case insert:
                    result = methodProxy.invokeSuper(obj, args);
                    AbstractEntity abstractEntity = (AbstractEntity) args[0];
                    updateAllFieldEntity(abstractEntity);
                    break;
                case update:
                    result = methodProxy.invokeSuper(obj, args);
                    abstractEntity = (AbstractEntity) args[0];
                    updateChangedFieldEntity(abstractEntity);
                    break;
                case query:
                    abstractEntity = (AbstractEntity) args[0];
                    if (abstractEntity != null) {
                        if (abstractEntity instanceof RedisInterface) {
                            RedisInterface redisInterface = (RedisInterface) abstractEntity;
                            result = redisService.getObjectFromHash(EntityUtils.getRedisKey(redisInterface), abstractEntity.getClass());
                        } else {
                            proxyLogger.error("query interface RedisListInterface " + abstractEntity.getClass().getSimpleName() + " use RedisInterface " + abstractEntity.toString());
                        }
                    }
                    if (result == null) {
                        result = methodProxy.invokeSuper(obj, args);
                        if(result != null){
                            abstractEntity = (AbstractEntity) result;
                            updateAllFieldEntity(abstractEntity);
                        }
                    }
                    break;
                case queryList:
                    abstractEntity = (AbstractEntity) args[0];
                    if (abstractEntity != null) {
                        if (abstractEntity instanceof RedisListInterface) {
                            RedisListInterface redisInterface = (RedisListInterface) abstractEntity;
                            result = redisService.getListFromHash(EntityUtils.getRedisKeyByRedisListInterface(redisInterface), abstractEntity.getClass());
                            if(result != null){
                                result = filterEntity((List<IEntity>) result, abstractEntity);
                            }
                        } else {
                            proxyLogger.error("query interface RedisInterface " + abstractEntity.getClass().getSimpleName() + " use RedisListInterface " + abstractEntity.toString());
                        }
                    }
                    if (result == null) {
                        result = methodProxy.invokeSuper(obj, args);
                        if(result != null){
                            List<AbstractEntity> entityList = (List<AbstractEntity>) result;
                            updateAllFieldEntityList(entityList);
                        }
                    }
                    break;
                case delete:
                    result = methodProxy.invokeSuper(obj, args);
                    abstractEntity = (AbstractEntity) args[0];
                    deleteEntity(abstractEntity);
                    break;
                case insertBatch:
                    result = methodProxy.invokeSuper(obj, args);
                    List<AbstractEntity> entityList = (List<AbstractEntity>) args[0];
                    updateAllFieldEntityList(entityList);
                    break;
                case updateBatch:
                    result = methodProxy.invokeSuper(obj, args);
                    entityList = (List<AbstractEntity>) args[0];
                    updateChangedFieldEntityList(entityList);
                    break;
                case deleteBatch:
                    result = methodProxy.invokeSuper(obj, args);
                    entityList = (List<AbstractEntity>) args[0];
                    deleteEntityList(entityList);
                    break;
            }
        }
        return result;
    }

    /**
     * 更新变化字段
     * @param entity
     */
    protected void updateChangedFieldEntity(AbstractEntity entity){
        if (entity != null) {
            if (entity instanceof RedisInterface) {
                RedisInterface redisInterface = (RedisInterface) entity;
                redisService.updateObjectHashMap(EntityUtils.getRedisKey(redisInterface), entity.getEntityProxyWrapper().getEntityProxy().getChangeParamSet());
            } else if (entity instanceof RedisListInterface) {
                RedisListInterface redisListInterface = (RedisListInterface) entity;
                List<RedisListInterface> redisListInterfaceList = new ArrayList<>();
                redisListInterfaceList.add(redisListInterface);
                redisService.setListToHash(EntityUtils.getRedisKeyByRedisListInterface(redisListInterface), redisListInterfaceList);
            }
        }
    }

    /**
     * 更新所有字段
     * @param entity
     */
    protected void updateAllFieldEntity(AbstractEntity entity){
        if (entity != null) {
            if (entity instanceof RedisInterface) {
                RedisInterface redisInterface = (RedisInterface) entity;
                redisService.setObjectToHash(EntityUtils.getRedisKey(redisInterface), entity);
            } else if (entity instanceof RedisListInterface) {
                RedisListInterface redisListInterface = (RedisListInterface) entity;
                List<RedisListInterface> redisListInterfaceList = new ArrayList<>();
                redisListInterfaceList.add(redisListInterface);
                redisService.setListToHash(EntityUtils.getRedisKeyByRedisListInterface(redisListInterface), redisListInterfaceList);
            }
        }
    }

    /**
     * 删除实体
     * @param abstractEntity
     */
    protected void deleteEntity(AbstractEntity abstractEntity){
        if (abstractEntity != null) {
            if (abstractEntity instanceof RedisInterface) {
                RedisInterface redisInterface = (RedisInterface) abstractEntity;
                redisService.deleteKey(EntityUtils.getRedisKey(redisInterface));
            }else if(abstractEntity instanceof RedisListInterface){
                RedisListInterface redisListInterface = (RedisListInterface) abstractEntity;
                redisService.hdel(EntityUtils.getRedisKeyByRedisListInterface(redisListInterface), redisListInterface.getSubUniqueKey());
            }
        }
    }

    /**
     * 更新所有字段实体列表
     * @param entityList
     */
    public void updateAllFieldEntityList(List<AbstractEntity> entityList){
        //拿到第一个，看一下类型
        if(entityList.size() > 0){
            AbstractEntity entity = entityList.get(0);
            if(entity instanceof  RedisInterface){
                for(AbstractEntity abstractEntity : entityList){
                    updateAllFieldEntity(entity);
                }
            }else if(entity instanceof RedisListInterface){
                List<RedisListInterface> redisListInterfaceList = new ArrayList<>();
                for(AbstractEntity abstractEntity : entityList){
                    redisListInterfaceList.add((RedisListInterface) abstractEntity);
                }
                redisService.setListToHash(EntityUtils.getRedisKeyByRedisListInterface((RedisListInterface) entity), redisListInterfaceList);
            }
        }
    }

    /**
     * 更新变化字段实体列表
     * @param entityList
     */
    protected void updateChangedFieldEntityList(List<AbstractEntity> entityList){
        if(entityList.size() > 0) {
            AbstractEntity entity = entityList.get(0);
            if (entity != null) {
                if (entity instanceof RedisInterface) {
                    for(AbstractEntity abstractEntity : entityList){
                        updateChangedFieldEntity(entity);
                    }
                } else if (entity instanceof RedisListInterface) {

                    List<RedisListInterface> redisListInterfaceList = new ArrayList<>();
                    for(AbstractEntity abstractEntity : entityList) {
                        RedisListInterface redisListInterface = (RedisListInterface) abstractEntity;
                        redisListInterfaceList.add(redisListInterface);
                    }
                    redisService.setListToHash(EntityUtils.getRedisKeyByRedisListInterface((RedisListInterface) entity), redisListInterfaceList);
                }
            }
        }
    }

    //删除实体列表
    protected void deleteEntityList(List<AbstractEntity> entityList){
        if(entityList.size() > 0) {
            AbstractEntity entity = entityList.get(0);
            if (entity != null) {
                if (entity instanceof RedisInterface) {
                    for(AbstractEntity abstractEntity : entityList){
                        deleteEntity(abstractEntity);
                    }
                } else if (entity instanceof RedisListInterface) {
                    List<String> redisListInterfaceList = new ArrayList<>();
                    for(AbstractEntity abstractEntity : entityList) {
                        RedisListInterface redisListInterface = (RedisListInterface) abstractEntity;
                        redisListInterfaceList.add(redisListInterface.getSubUniqueKey());
                    }
                    redisService.hdel(EntityUtils.getRedisKeyByRedisListInterface((RedisListInterface) entity), redisListInterfaceList.toArray(new String[0]));
                }
            }
        }
    }

    /**
     * 根据封装的条件判断查找出相同的对象
     * @param list
     * @param abstractEntity
     * @return
     */
    public List<IEntity> filterEntity(List<IEntity> list, AbstractEntity abstractEntity){
        List<IEntity> result = new ArrayList<>();
        //开始进行filter
        EntityProxyWrapper entityProxyWrapper = abstractEntity.getEntityProxyWrapper();
        if(entityProxyWrapper != null){
            Map<String, Object>  changeParamSet = entityProxyWrapper.getEntityProxy().getChangeParamSet();
            if(changeParamSet != null){
                for(IEntity iEntity: list) {
                    boolean equalFlag = false;
                    for (String fieldName : changeParamSet.keySet()) {
                        String value = ObjectUtils.getFieldsValueStr(iEntity, fieldName);

                        Object object = changeParamSet.get(fieldName);
                        if(value.equals(object.toString())){
                            equalFlag = true;
                        }else{
                            equalFlag = false;
                            break;
                        }
                    }

                    if(equalFlag){
                        result.add(iEntity);
                    }

                }
            }
        }
        return result;
    }
}
