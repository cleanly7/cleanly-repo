package com.sky.service.impl;

import com.fasterxml.jackson.databind.ser.Serializers;
import com.sky.context.BaseContext;
import com.sky.dto.ShoppingCartDTO;
import com.sky.entity.Setmeal;
import com.sky.entity.ShoppingCart;
import com.sky.exception.ShoppingCartBusinessException;
import com.sky.mapper.DishMapper;
import com.sky.mapper.SetmealMapper;
import com.sky.mapper.ShoppingCartMapper;
import com.sky.service.ShoppingCartService;
import com.sky.vo.DishVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ShoppingCartServiceImpl implements ShoppingCartService {
    @Autowired
    private ShoppingCartMapper shoppingCartMapper;
    @Autowired
    private DishMapper dishMapper;
    @Autowired
    private SetmealMapper setmealMapper;

    @Override
    public void add(ShoppingCartDTO shoppingCartDTO) {
        ShoppingCart shoppingCart = new ShoppingCart();
        shoppingCart.setUserId(BaseContext.getCurrentId());
        shoppingCart.setDishId(shoppingCartDTO.getDishId());
        shoppingCart.setSetmealId(shoppingCartDTO.getSetmealId());
        shoppingCart.setDishFlavor(shoppingCartDTO.getDishFlavor());

        if (shoppingCartDTO.getDishId() != null) {
            DishVO dish = dishMapper.getById(shoppingCartDTO.getDishId());
            if (dish == null || dish.getStatus() == 0) {
                throw new ShoppingCartBusinessException("该菜品已停售，无法加入购物车");
            }
            shoppingCart.setImage(dish.getImage());
            shoppingCart.setName(dish.getName());
            shoppingCart.setAmount(dish.getPrice());
        }

        if (shoppingCartDTO.getSetmealId() != null) {
            Setmeal setmeal = setmealMapper.getById(shoppingCartDTO.getSetmealId());
            if (setmeal == null || setmeal.getStatus() == 0) {
                throw new ShoppingCartBusinessException("该套餐已停售，无法加入购物车");
            }
            shoppingCart.setImage(setmeal.getImage());
            shoppingCart.setName(setmeal.getName());
            shoppingCart.setAmount(setmeal.getPrice());
        }

        ShoppingCart exist = shoppingCartMapper.getByShoppingCart(shoppingCart);
        if (exist == null) {
            shoppingCart.setNumber(1);
            shoppingCart.setCreateTime(LocalDateTime.now());
            shoppingCartMapper.add(shoppingCart);
        } else {
            exist.setNumber(exist.getNumber() + 1);
            shoppingCartMapper.updateNumber(exist);
        }
    }

    public List<ShoppingCart> list() {
        long userId = BaseContext.getCurrentId();
        return shoppingCartMapper.list(userId);
    }

    public void clean() {
        long userId = BaseContext.getCurrentId();
        shoppingCartMapper.clean(userId);
    }

    public void sub(ShoppingCartDTO shoppingCartDTO) {
        //获取用户
        long userId = BaseContext.getCurrentId();
        //获取购物车商品
        ShoppingCart shoppingCart = new ShoppingCart();
        shoppingCart.setUserId(userId);
        shoppingCart.setDishId(shoppingCartDTO.getDishId());
        shoppingCart.setSetmealId(shoppingCartDTO.getSetmealId());
        shoppingCart.setDishFlavor(shoppingCartDTO.getDishFlavor());
        ShoppingCart exist = shoppingCartMapper.getByShoppingCart(shoppingCart);
        if (exist.getNumber() > 1) {
            //若为多个，将数量减一
            exist.setNumber(exist.getNumber() - 1);
            shoppingCartMapper.updateNumber(exist);
        } else {
            //若为一个，将该商品从购物车删除
            shoppingCartMapper.delete(exist);
        }


    }
}
