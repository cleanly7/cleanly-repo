package com.sky.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.MessageConstant;
import com.sky.dto.DishDTO;
import com.sky.dto.DishPageQueryDTO;
import com.sky.entity.Dish;
import com.sky.entity.DishFlavor;
import com.sky.exception.DeletionNotAllowedException;
import com.sky.mapper.DishFlavorMapper;
import com.sky.mapper.DishMapper;
import com.sky.mapper.SetmealDishMapper;
import com.sky.result.PageResult;
import com.sky.result.Result;
import com.sky.service.DishService;
import com.sky.vo.DishVO;
import io.swagger.annotations.SwaggerDefinition;
import org.apache.xmlbeans.impl.xb.xsdschema.Public;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;


@Service
public class DishServiceImpl implements DishService {
    @Autowired
    private DishMapper dishMapper;
    @Autowired
    private SetmealDishMapper setmealDishMapper;
    @Autowired
    private DishFlavorMapper dishFlavorMapper;
    @Transactional
    public Result saveWithFlavor(DishDTO dishDTO){

        Dish dish = new Dish();
        BeanUtils.copyProperties(dishDTO, dish);
        dishMapper.saveDish(dish);

        Long dishId = dish.getId();

        List<DishFlavor> dishFlavors = dishDTO.getFlavors();
        if(dishFlavors != null && dishFlavors.size() > 0) {
            dishFlavors.forEach(dishFlavor -> dishFlavor.setDishId(dishId));
            dishFlavorMapper.saveDishFlavor(dishFlavors);
        }
        return Result.success();
    }
    /**
     * 菜品分页查询
     *
     * @param dishPageQueryDTO
     * @return
     */
    public PageResult pageQuery(DishPageQueryDTO dishPageQueryDTO){
        PageHelper.startPage(dishPageQueryDTO.getPage(), dishPageQueryDTO.getPageSize());
        Page<DishVO> page = dishMapper.pageQuery(dishPageQueryDTO);
        return new PageResult(page.getTotal(), page.getResult() );
    }

    /**
     * 删除菜品及口味
     *
     * @param ids
     */
    @Transactional
    public void delete(List<Long> ids){
        //判断是否可以删除
            //是否被套餐关联
                if (!setmealDishMapper.getSetmealIdsByDishIds(ids).isEmpty()) {
                    throw new DeletionNotAllowedException(MessageConstant.DISH_BE_RELATED_BY_SETMEAL);
                }
            //是否启售中
                if (dishMapper.checkStatusByIds(ids) > 0) {
                    throw new DeletionNotAllowedException(MessageConstant.DISH_ON_SALE);
                }
        //删除菜品和口味
        dishFlavorMapper.deleteByDishIds(ids);
        dishMapper.deleteByDishIds(ids);
    }

    public DishVO getByIdWithFlavor(Long id){
        DishVO dishVO = dishMapper.getById(id);
        List<DishFlavor> dishFlavors = dishFlavorMapper.getByDishId(id);
        dishVO.setFlavors(dishFlavors);
        return dishVO;
    }

    @Transactional
    public void updateWithFlavor(DishDTO dishDTO){
        Dish dish = new Dish();
        BeanUtils.copyProperties(dishDTO, dish);
        dishMapper.update(dish);
        if(dishDTO.getFlavors() != null && dishDTO.getFlavors().size() > 0){
        dishFlavorMapper.deleteByDishIds(Collections.singletonList(dishDTO.getId()));
        dishFlavorMapper.saveDishFlavor(dishDTO.getFlavors());
        }
    }

    public void updateStatus(Long id, Integer status){
        LocalDateTime now = LocalDateTime.now();
        long currentId = Thread.currentThread().getId();
        dishMapper.updateStatus(id, status, now, currentId);
    }
}
