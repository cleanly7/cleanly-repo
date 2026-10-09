package com.sky.service.impl;


import com.sky.dto.GoodsSalesDTO;
import com.sky.entity.Orders;
import com.sky.entity.User;
import com.sky.mapper.OrderMapper;
import com.sky.mapper.UserMapper;
import com.sky.service.ReportService;
import com.sky.service.WorkspaceService;
import com.sky.vo.*;

import io.swagger.models.auth.In;
import org.apache.commons.lang3.StringUtils;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ReportServiceImpl implements ReportService {

    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private WorkspaceService workspaceService;
    public TurnoverReportVO getTurnoverStatistics(LocalDate begin, LocalDate end) {
        ArrayList<LocalDate> dateList = new ArrayList<>();
        ArrayList<Double> turnoverList = new ArrayList<>();

        dateList.add(begin);

        while(!begin.isEqual(end)){
            begin = begin.plusDays(1);
            dateList.add(begin);
        }

        for (LocalDate localDate : dateList) {
            LocalDateTime beginTime = LocalDateTime.of(localDate, LocalTime.MIN);
            LocalDateTime endTime = LocalDateTime.of(localDate, LocalTime.MAX);
            Map map = new HashMap();
            map.put("begin", beginTime);
            map.put("end", endTime);
            map.put("status", Orders.COMPLETED);
            Double turnover = orderMapper.getTurnoverByMap(map);
            turnover = turnover == null ? 0.0 : turnover;
            turnoverList.add(turnover);
        }

        // 注意：分隔符必须是英文逗号 ","（前端用 split(',') 解析，再用 parseFloat 转数值）。
        // 如果写成 "','"，前端得到的每一项会带单引号，parseFloat 会算出 NaN，折线图就画不出来。
        String dateListStr = StringUtils.join(dateList, ",");
        String turnoverListStr = StringUtils.join(turnoverList, ",");
        return new TurnoverReportVO(dateListStr, turnoverListStr);
    }

    public UserReportVO getUserReportVO(LocalDate begin, LocalDate end) {
        ArrayList<LocalDate> dateList = new ArrayList<>();
        ArrayList<Integer> totalUserList = new ArrayList<>();
        ArrayList<Integer> newUserList = new ArrayList<>();

        dateList.add(begin);

        while(!begin.isEqual(end)){
            begin = begin.plusDays(1);
            dateList.add(begin);
        }

        for (LocalDate localDate : dateList) {
            LocalDateTime beginTime = LocalDateTime.of(localDate, LocalTime.MIN);
            LocalDateTime endTime = LocalDateTime.of(localDate, LocalTime.MAX);
            Map map = new HashMap();
            map.put("begin", beginTime);
            map.put("end", endTime);
            Integer totalUser = userMapper.getTotalUser(map);
            Integer newUser = userMapper.getNewUser(map);
            totalUser = totalUser == null ? 0 : totalUser;
            newUser = newUser == null ? 0 : newUser;
            totalUserList.add(totalUser);
            newUserList.add(newUser);
        }

        String dateListStr = StringUtils.join(dateList, ",");
        String totalUserListStr = StringUtils.join(totalUserList, ",");
        String newUserListStr = StringUtils.join(newUserList, ",");
        return new UserReportVO(dateListStr, totalUserListStr, newUserListStr);
    }

    public OrderReportVO getOrderReportVO(LocalDate begin, LocalDate end) {
        ArrayList<LocalDate> dateList = new ArrayList<>();
        double orderCompletionRate;

        ArrayList<Integer> orderCountList = new ArrayList<>();
        ArrayList<Integer> validOrderCountList = new ArrayList<>();
        LocalDateTime beginTime = LocalDateTime.of(begin, LocalTime.MIN);
        LocalDateTime endTime = LocalDateTime.of(end, LocalTime.MAX);
        Integer totalOrderCount = 0;
        Integer validOrderCount = 0;

        dateList.add(begin);

        while(!begin.isEqual(end)){
            begin = begin.plusDays(1);
            dateList.add(begin);
        }

        Map map = new HashMap();
        map.put("begin", beginTime);
        map.put("end", endTime);
        //总订单数
        totalOrderCount = orderMapper.getOrderCountByMap(map);
        //有效订单
        map.put("status", Orders.COMPLETED);
        validOrderCount = orderMapper.getOrderCountByMap(map);

        orderCompletionRate = (double) validOrderCount / totalOrderCount;

        for (LocalDate localDate : dateList) {
            LocalDateTime beginTimeOfDay = LocalDateTime.of(localDate, LocalTime.MIN);
            LocalDateTime endTimeOfDay = LocalDateTime.of(localDate, LocalTime.MAX);
            Map dayMap = new HashMap();
            dayMap.put("begin", beginTimeOfDay);
            dayMap.put("end", endTimeOfDay);
            //总订单数
            Integer orderCount = orderMapper.getOrderCountByMap(dayMap);
            //有效订单
            dayMap.put("status", Orders.COMPLETED);
            Integer validOrderDayCount = orderMapper.getOrderCountByMap(dayMap);
            orderCountList.add(orderCount);
            validOrderCountList.add(validOrderDayCount);
        }
        return OrderReportVO.builder()
                .dateList(StringUtils.join(dateList, ","))
                .orderCountList(StringUtils.join(orderCountList, ","))
                .validOrderCountList(StringUtils.join(validOrderCountList, ","))
                .totalOrderCount(totalOrderCount)
                .validOrderCount(validOrderCount)
                .orderCompletionRate(orderCompletionRate)
                .build();
    }

    public SalesTop10ReportVO getSalesTop10ReportVO(LocalDate begin, LocalDate end) {
        LocalDateTime beginTime = LocalDateTime.of(begin, LocalTime.MIN);
        LocalDateTime endTime = LocalDateTime.of(end, LocalTime.MAX);
        List<GoodsSalesDTO> goodsSalesDTOList = orderMapper.getGoodsSales(beginTime, endTime);
        List<String> nameList = goodsSalesDTOList.stream().map(GoodsSalesDTO::getName).collect(Collectors.toList());
        String nameListStr = StringUtils.join(nameList, ",");
        List<Integer> numberList = goodsSalesDTOList.stream().map(GoodsSalesDTO::getNumber).collect(Collectors.toList());
        String numberListStr = StringUtils.join(numberList, ",");
        return SalesTop10ReportVO.builder()
                .nameList(nameListStr)
                .numberList(numberListStr)
                .build();
    }

    public void export(HttpServletResponse response){
        //1.查询数据库--查询最近30天数据
        LocalDate begin = LocalDate.now().minusDays(30);
        LocalDate end = LocalDate.now().minusDays(1);
        LocalDateTime beginTime = LocalDateTime.of(begin, LocalTime.MIN);
        LocalDateTime endTime = LocalDateTime.of(end, LocalTime.MAX);
        //查询概览数据
        BusinessDataVO businessDataVO = workspaceService.getBusinessData(beginTime, endTime);
        //2.通过POI写入Excel
        InputStream in = this.getClass().getClassLoader().getResourceAsStream("template/运营数据报表模板.xlsx");
        try{
            //基于已有文件创建新文件
            XSSFWorkbook excel = new XSSFWorkbook(in);

            //获取模版页
            XSSFSheet sheet = excel.getSheet("sheet1");

            //填充数据--时间
            sheet.getRow(1).getCell(1).setCellValue("时间: " + beginTime + "至" + endTime);

            //获得第四行
            XSSFRow row = sheet.getRow(3);
            row.getCell(2).setCellValue(businessDataVO.getTurnover());
            row.getCell(4).setCellValue(businessDataVO.getOrderCompletionRate());
            row.getCell(6).setCellValue(businessDataVO.getNewUsers());

            //获得第五行
            XSSFRow row2 = sheet.getRow(4);
            row2.getCell(2).setCellValue(businessDataVO.getValidOrderCount());
            row2.getCell(2).setCellValue(businessDataVO.getUnitPrice());

            //获得每天的明细数据
            for(int i=0;i<30;i++) {
                LocalDate localDate = LocalDate.now().minusDays(i+1);
                LocalDateTime beginTimeOfDay = LocalDateTime.of(localDate, LocalTime.MIN);
                LocalDateTime endTimeOfDay = LocalDateTime.of(localDate, LocalTime.MAX);
                businessDataVO = workspaceService.getBusinessData(beginTimeOfDay, endTimeOfDay);

                    sheet.getRow(i+7).getCell(1).setCellValue(localDate.toString());
                    sheet.getRow(i+7).getCell(2).setCellValue(businessDataVO.getTurnover());
                    sheet.getRow(i+7).getCell(3).setCellValue(businessDataVO.getValidOrderCount());
                    sheet.getRow(i+7).getCell(4).setCellValue(businessDataVO.getOrderCompletionRate());
                    sheet.getRow(i+7).getCell(5).setCellValue(businessDataVO.getUnitPrice());
                    sheet.getRow(i+7).getCell(6).setCellValue(businessDataVO.getNewUsers());
            }
            //3.通过response下载Excel
            ServletOutputStream out = response.getOutputStream();
            excel.write(out);

            out.close();
            excel.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }
}
