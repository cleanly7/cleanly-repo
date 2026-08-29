package com.sky.aspect;

import com.sky.annotation.Autofill;
import com.sky.constant.AutoFillConstant;
import com.sky.enumeration.OperationType;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.LocalDateTime;

/**
 * 自定义切面，实现自动填充功能
 */
@Aspect
@Component
@Slf4j
public class AutoFillAspect {
    /**
     * 切入点
     */
    @Pointcut("execution(* com.sky.mapper.*.*(..)) && @annotation(com.sky.annotation.Autofill)")
    public void autoFillPointCut(){
        log.info("切入点执行了");

    }

    /**
     * 前置通知,进行赋值
     */
    @Before("autoFillPointCut()")
    public void autoFill(JoinPoint joinPoint) throws NoSuchFieldException, IllegalAccessException, InvocationTargetException, NoSuchMethodException {
        log.info("开始进行公共字段自动填充...");
        //获取拦截方法的操作类型
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();//获取方法签名
        Autofill autofill = signature.getMethod().getAnnotation(Autofill.class);//获取方法上的注解
        OperationType operationType = autofill.value();//获取注解中的操作类型

        //获取当前拦截方法参数 -- 实体对象
        Object[] args = joinPoint.getArgs();
        if(args == null || args.length == 0){
            log.info("没有参数");
            return;
        }
        Object entity = args[0];

        //准备当前数据
        long currentId = Thread.currentThread().getId();
        LocalDateTime now = LocalDateTime.now();

        //根据当前操作类型，利用反射，继续赋值操作
        if(operationType == OperationType.INSERT){
            //为四个公共字段赋值
            Method setCreateTime = entity.getClass().getDeclaredMethod(AutoFillConstant.SET_CREATE_TIME, LocalDateTime.class);
            Method setUpdateTime = entity.getClass().getDeclaredMethod(AutoFillConstant.SET_UPDATE_TIME, LocalDateTime.class);
            Method setCreator = entity.getClass().getDeclaredMethod(AutoFillConstant.SET_CREATE_USER, Long.class);
            Method setUpdater = entity.getClass().getDeclaredMethod(AutoFillConstant.SET_UPDATE_USER, Long.class);

            setCreateTime.invoke(entity, now);
            setUpdateTime.invoke(entity, now);
            setCreator.invoke(entity, currentId);
            setUpdater.invoke(entity, currentId);

            log.info("插入操作的自动填充完成");
        }else if(operationType == OperationType.UPDATE){
            //为两个公共字段赋值
            Method setUpdateTime = entity.getClass().getDeclaredMethod(AutoFillConstant.SET_UPDATE_TIME, LocalDateTime.class);
            Method setUpdater = entity.getClass().getDeclaredMethod(AutoFillConstant.SET_UPDATE_USER , Long.class);

            setUpdateTime.invoke(entity, now);
            setUpdater.invoke(entity, currentId);

            log.info("更新操作的自动填充完成");
        }
    }
}
