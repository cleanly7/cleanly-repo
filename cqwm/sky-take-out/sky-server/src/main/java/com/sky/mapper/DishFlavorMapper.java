package com.sky.mapper;

import com.sky.entity.DishFlavor;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface DishFlavorMapper {

    void saveDishFlavor(List<DishFlavor> dishFlavors);

    void deleteByDishIds(List<Long> ids);

    List<DishFlavor> getByDishId(Long id);

}
