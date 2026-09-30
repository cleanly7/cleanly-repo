package com.sky.mapper;

import com.sky.dto.ShoppingCartDTO;
import com.sky.entity.ShoppingCart;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface ShoppingCartMapper {
    void add(ShoppingCart shoppingCart);

    ShoppingCart getByShoppingCart(ShoppingCart shoppingCart);

    void updateNumber(ShoppingCart shoppingCart);

    List<ShoppingCart> list(long userId);

    void clean(long userId);

    void delete(ShoppingCart exist);
}
