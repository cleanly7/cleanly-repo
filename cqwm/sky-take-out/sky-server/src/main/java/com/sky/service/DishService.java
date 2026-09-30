package com.sky.service;

import com.sky.dto.DishDTO;
import com.sky.dto.DishPageQueryDTO;
import com.sky.entity.Dish;
import com.sky.result.PageResult;
import com.sky.result.Result;
import com.sky.vo.DishVO;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Transactional
public interface DishService {

    /**
     * 新增菜品及口味
     *
     * @param dishDTO
     */
    public Result saveWithFlavor(DishDTO dishDTO);

    /**
     * 菜品分页查询
     *
     * @param dishPageQueryDTO
     * @return
     */
    PageResult pageQuery(DishPageQueryDTO dishPageQueryDTO);

    /**
     * 批量删除菜品
     * @param ids
     */
    void delete(List<Long> ids);

    /**
     * 根据id查询菜品
     * @param id
     * @return
     */
    DishVO getByIdWithFlavor(Long id);

    void updateWithFlavor(DishDTO dishDTO);

    void updateStatus(Long id, Integer status);
    

    /**
     * 条件查询菜品和口味（用户端商品浏览）
     * @param dish
     * @return
     */
    List<DishVO> listWithFlavor(Dish dish);

    /**
     * 根据分类id查询菜品（套餐管理-添加菜品窗口用）
     * @param categoryId
     * @return
     */
    List<Dish> list(Long categoryId);
}
