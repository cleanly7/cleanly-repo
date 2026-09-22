package com.sky.mapper;

import com.github.pagehelper.Page;
import com.sky.annotation.Autofill;
import com.sky.dto.DishDTO;
import com.sky.dto.DishPageQueryDTO;
import com.sky.entity.Dish;
import com.sky.entity.DishFlavor;
import com.sky.enumeration.OperationType;
import com.sky.vo.DishVO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface DishMapper {

    /**
     * 根据分类id查询菜品数量
     * @param categoryId
     * @return
     */
    @Select("select count(*) from dish where category_id = #{categoryId}")
    int countByCategoryId(Long categoryId);

    /**
     * 新增菜品
     * @param dish
     */
    @Autofill(OperationType.INSERT)
    void saveDish(Dish dish);

    /**
     * 分页查询
     * @param dishPageQueryDTO
     * @return
     */
    Page<DishVO> pageQuery(DishPageQueryDTO dishPageQueryDTO);

    /**
     * 根据id删除菜品
     * @param ids
     */
    void deleteByDishIds(List<Long> ids);

    /**
     * 根据id批量检查菜品状态
     * @param ids
     * @return
     */
    int checkStatusByIds(List<Long> ids);

    /**
     * 根据id查询菜品
     * @param id
     * @return
     */
    DishVO getById(Long id);

    /**
     * 修改菜品
     * @param dish
     */
    @Autofill(OperationType.UPDATE)
    void update(Dish dish);

    void updateStatus(@Param("id") Long id, @Param("status") Integer status, @Param("updateTime") LocalDateTime now, @Param("updateUser") Long currentId);
}
