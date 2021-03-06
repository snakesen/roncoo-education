package com.roncoo.education.course.service.auth.biz;

import cn.hutool.core.util.ObjectUtil;
import com.github.abel533.echarts.Option;
import com.github.abel533.echarts.axis.CategoryAxis;
import com.github.abel533.echarts.axis.ValueAxis;
import com.github.abel533.echarts.code.AxisType;
import com.github.abel533.echarts.code.Magic;
import com.github.abel533.echarts.code.Tool;
import com.github.abel533.echarts.code.Trigger;
import com.github.abel533.echarts.feature.MagicType;
import com.github.abel533.echarts.series.Line;
import com.roncoo.education.common.core.base.*;
import com.roncoo.education.common.core.config.SystemUtil;
import com.roncoo.education.common.core.enums.*;
import com.roncoo.education.common.core.pay.PayUtil;
import com.roncoo.education.common.core.tools.BeanUtil;
import com.roncoo.education.common.core.tools.DateUtil;
import com.roncoo.education.common.core.tools.NOUtil;
import com.roncoo.education.course.dao.CourseDao;
import com.roncoo.education.course.dao.OrderInfoDao;
import com.roncoo.education.course.dao.OrderPayDao;
import com.roncoo.education.course.dao.impl.mapper.entity.Course;
import com.roncoo.education.course.dao.impl.mapper.entity.OrderInfo;
import com.roncoo.education.course.dao.impl.mapper.entity.OrderInfoExample;
import com.roncoo.education.course.dao.impl.mapper.entity.OrderInfoExample.Criteria;
import com.roncoo.education.course.dao.impl.mapper.entity.OrderPay;
import com.roncoo.education.course.service.api.bo.OrderInfoCloseBO;
import com.roncoo.education.course.service.auth.bo.*;
import com.roncoo.education.course.service.auth.dto.*;
import com.roncoo.education.system.feign.interfaces.IFeignSys;
import com.roncoo.education.system.feign.interfaces.vo.SysVO;
import com.roncoo.education.user.feign.interfaces.IFeignLecturer;
import com.roncoo.education.user.feign.interfaces.IFeignUserExt;
import com.roncoo.education.user.feign.interfaces.vo.LecturerVO;
import com.roncoo.education.user.feign.interfaces.vo.UserExtVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

/**
 * ???????????????
 *
 * @author wujing
 */
@Component
public class AuthApiOrderInfoBiz extends BaseBiz {

    @Autowired
    private OrderInfoDao orderInfoDao;
    @Autowired
    private OrderPayDao orderPayDao;
    @Autowired
    private CourseDao courseDao;

    @Autowired
    private IFeignSys bossSys;
    @Autowired
    private IFeignUserExt bossUserExt;
    @Autowired
    private IFeignLecturer bossLecturer;

    /**
     * ??????????????????
     *
     * @param authOrderInfoListBO
     * @return
     */
    public Result<Page<AuthOrderInfoListDTO>> listForPage(AuthOrderInfoListBO authOrderInfoListBO) {
        OrderInfoExample Example = new OrderInfoExample();
        Criteria c = Example.createCriteria();
        c.andUserNoEqualTo(authOrderInfoListBO.getUserNo());
        c.andIsShowUserEqualTo(IsShowEnum.YES.getCode());
        // ???????????????
        if (StringUtils.isEmpty(authOrderInfoListBO.getPageCurrent())) {
            authOrderInfoListBO.setPageCurrent(1);
        }
        if (StringUtils.isEmpty(authOrderInfoListBO.getPageSize())) {
            authOrderInfoListBO.setPageSize(20);
        }
        // 0??????????????????,???????????????????????????????????????
        if (authOrderInfoListBO.getOrderStatus() != null && !authOrderInfoListBO.getOrderStatus().equals(Integer.valueOf(0))) {
            c.andOrderStatusEqualTo(authOrderInfoListBO.getOrderStatus());
        } else {
            c.andOrderStatusNotEqualTo(OrderStatusEnum.CLOSE.getCode());
        }
        Example.setOrderByClause(" id desc ");
        Page<OrderInfo> page = orderInfoDao.listForPage(authOrderInfoListBO.getPageCurrent(), authOrderInfoListBO.getPageSize(), Example);
        Page<AuthOrderInfoListDTO> dtopage = PageUtil.transform(page, AuthOrderInfoListDTO.class);
        for (AuthOrderInfoListDTO dto : dtopage.getList()) {
            Course course = courseDao.getById(dto.getCourseId());
            dto.setCourseLogo(course.getCourseLogo());
            dto.setCourseId(course.getId());
        }
        return Result.success(dtopage);
    }

    /**
     * ??????????????????
     *
     * @param authOrderPayBO
     * @return
     */
    @Transactional
    public Result<AuthOrderPayDTO> pay(AuthOrderPayBO authOrderPayBO) {
        // ????????????
        verifyParam(authOrderPayBO);

        // ????????????
        Course course = courseDao.getByCourseIdAndStatusId(authOrderPayBO.getCourseId(), StatusIdEnum.YES.getCode());
        if (StringUtils.isEmpty(course)) {
            return Result.error("courseId?????????");
        }

        // ????????????????????????????????????
        UserExtVO userextVO = bossUserExt.getByUserNo(authOrderPayBO.getUserNo());
        if (ObjectUtil.isNull(userextVO) || StatusIdEnum.NO.getCode().equals(userextVO.getStatusId())) {
            return Result.error("userNo?????????");
        }

        // ??????????????????
        LecturerVO lecturerVO = bossLecturer.getByLecturerUserNo(course.getLecturerUserNo());
        if (StringUtils.isEmpty(lecturerVO) || !StatusIdEnum.YES.getCode().equals(lecturerVO.getStatusId())) {
            return Result.error("lecturerUserNo?????????");
        }

        // ?????????????????????????????????????????????------(???????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????)
        if (!checkOrderInfo(authOrderPayBO.getUserNo(), authOrderPayBO.getCourseId()) && !SystemUtil.TEST_COURSE.equals(course.getId().toString())) {
            return Result.error("??????????????????????????????????????????");
        }

        // ??????????????????
        OrderInfo orderInfo = createOrderInfo(authOrderPayBO, course, userextVO, lecturerVO);

        // ??????????????????
        OrderPay orderPay = createOrderPay(orderInfo);

        // ????????????????????????
        SysVO sys = bossSys.getSys();
        if (ObjectUtil.isNull(sys)) {
            return Result.error("???????????????????????????");
        }
        if (StringUtils.isEmpty(sys.getPayKey()) || StringUtils.isEmpty(sys.getPaySecret()) || StringUtils.isEmpty(sys.getPayUrl())) {
            return Result.error("payKey,paySecret???payUrl?????????");
        }

        // ??????????????????
        String payMessage = PayUtil.roncooPay(String.valueOf(orderPay.getSerialNumber()), orderInfo.getCourseName(), orderInfo.getPricePaid(), orderInfo.getPayType(), sys.getPayKey(), sys.getPaySecret(), sys.getPayUrl(), sys.getNotifyUrl());
        if (StringUtils.isEmpty(payMessage)) {
            return Result.error("??????????????????????????????");
        }

        // ????????????
        AuthOrderPayDTO dto = new AuthOrderPayDTO();
        dto.setPayMessage(payMessage);
        dto.setOrderNo(String.valueOf(orderInfo.getOrderNo()));
        dto.setCourseName(orderInfo.getCourseName());
        dto.setPayType(orderInfo.getPayType());
        dto.setPrice(orderInfo.getPricePaid());
        return Result.success(dto);
    }

    /**
     * ????????????????????????
     *
     * @param orderInfoContinuePayBO
     * @return
     */
    @Transactional
    public Result<AuthOrderPayDTO> continuePay(AuthOrderInfoContinuePayBO authOrderInfoContinuePayBO) {
        if (StringUtils.isEmpty(authOrderInfoContinuePayBO.getOrderNo())) {
            return Result.error("orderNo????????????");
        }
        if (StringUtils.isEmpty(authOrderInfoContinuePayBO.getPayType())) {
            return Result.error("payType????????????");
        }

        // ????????????
        OrderInfo orderInfo = orderInfoDao.getByOrderNo(authOrderInfoContinuePayBO.getOrderNo());
        if (ObjectUtil.isNull(orderInfo)) {
            return Result.error("orderNo????????????????????????????????????");
        }
        if (!checkOrderInfo(orderInfo.getUserNo(), orderInfo.getCourseId())) {
            return Result.error("??????????????????????????????????????????");
        }

        OrderPay orderPay = orderPayDao.getByOrderNo(orderInfo.getOrderNo());
        if (ObjectUtil.isNull(orderPay)) {
            return Result.error("orderNo?????????????????????????????????");
        }

        // ??????????????????
        Course course = courseDao.getByCourseIdAndStatusId(orderInfo.getCourseId(), StatusIdEnum.YES.getCode());
        if (StringUtils.isEmpty(course) || !StatusIdEnum.YES.getCode().equals(course.getStatusId())) {
            return Result.error("?????????????????????????????????????????????????????????!");
        }
        // ????????????????????????????????????
        UserExtVO userExtVO = bossUserExt.getByUserNo(orderInfo.getUserNo());
        if (StringUtils.isEmpty(userExtVO) || !StatusIdEnum.YES.getCode().equals(userExtVO.getStatusId())) {
            return Result.error("???????????????userNo??????????????????????????????!");
        }

        // ??????????????????
        orderInfo.setPayType(authOrderInfoContinuePayBO.getPayType());
        orderInfo.setOrderStatus(OrderStatusEnum.WAIT.getCode());
        orderInfoDao.updateById(orderInfo);

        // ??????????????????????????????
        orderPay.setOrderStatus(OrderStatusEnum.WAIT.getCode());
        orderPay.setSerialNumber(NOUtil.getSerialNumber());
        orderPayDao.updateById(orderPay);

        // ????????????????????????
        SysVO sys = bossSys.getSys();
        if (ObjectUtil.isNull(sys)) {
            return Result.error("???????????????????????????");
        }
        if (StringUtils.isEmpty(sys.getPayKey()) || StringUtils.isEmpty(sys.getPaySecret()) || StringUtils.isEmpty(sys.getPayUrl())) {
            return Result.error("payKey,paySecret???payUrl?????????");
        }

        // ??????????????????
        String payMessage = PayUtil.roncooPay(String.valueOf(orderPay.getSerialNumber()), orderInfo.getCourseName(), orderInfo.getPricePaid(), orderInfo.getPayType(), sys.getPayKey(), sys.getPaySecret(), sys.getPayUrl(), sys.getNotifyUrl());
        if (StringUtils.isEmpty(payMessage)) {
            return Result.error("??????????????????????????????");
        }

        // ????????????
        AuthOrderPayDTO dto = new AuthOrderPayDTO();
        dto.setPayMessage(payMessage);
        dto.setOrderNo(String.valueOf(orderInfo.getOrderNo()));
        dto.setCourseName(orderInfo.getCourseName());
        dto.setPayType(orderInfo.getPayType());
        dto.setPrice(orderInfo.getPricePaid());
        return Result.success(dto);
    }

    /**
     * ????????????????????????
     *
     * @param continuePay
     * @return
     */
    @Transactional
    public Result<String> close(OrderInfoCloseBO orderInfoCloseBO) {
        if (StringUtils.isEmpty(orderInfoCloseBO.getOrderNo())) {
            return Result.error("orderNo????????????");
        }
        OrderInfo orderInfo = orderInfoDao.getByOrderNo(orderInfoCloseBO.getOrderNo());
        if (ObjectUtil.isNull(orderInfo)) {
            return Result.error("orderNo?????????,?????????????????????");
        }
        OrderPay orderPay = orderPayDao.getByOrderNo(orderInfo.getOrderNo());
        if (ObjectUtil.isNull(orderPay)) {
            return Result.error("orderNo?????????,??????????????????");
        }
        if (!OrderStatusEnum.WAIT.getCode().equals(orderInfo.getOrderStatus())) {
            return Result.error("????????????????????????????????????????????????");
        }
        // ????????????????????????????????????
        UserExtVO userExtVO = bossUserExt.getByUserNo(orderInfo.getUserNo());
        if (StringUtils.isEmpty(userExtVO) || !StatusIdEnum.YES.getCode().equals(userExtVO.getStatusId())) {
            return Result.error("???????????????userNo??????????????????????????????!");
        }
        orderInfo.setOrderStatus(OrderStatusEnum.CLOSE.getCode());
        int orderNum = orderInfoDao.updateById(orderInfo);
        if (orderNum < 1) {
            throw new BaseException("????????????????????????");
        }
        orderPay.setOrderStatus(OrderStatusEnum.CLOSE.getCode());
        int orderPayNum = orderPayDao.updateById(orderPay);
        if (orderPayNum < 1) {
            throw new BaseException("???????????????????????????");
        }
        return Result.success("??????????????????");
    }

    /**
     * ????????????
     *
     * @param orderInfoBO
     * @return
     */
    public Result<AuthOrderInfoDTO> view(AuthOrderInfoViewBO authOrderInfoViewBO) {
        if (StringUtils.isEmpty(authOrderInfoViewBO.getOrderNo())) {
            return Result.error("orderNo????????????");
        }
        // ????????????????????????????????????
        OrderInfo order = orderInfoDao.getByOrderNo(authOrderInfoViewBO.getOrderNo());
        if (ObjectUtil.isNull(order)) {
            return Result.error("orderNo?????????");
        }
        return Result.success(BeanUtil.copyProperties(order, AuthOrderInfoDTO.class));
    }

    /**
     * ??????????????????????????????
     *
     * @param authOrderInfoListBO
     * @return
     */
    public Result<Page<AuthOrderInfoListForLecturerDTO>> list(AuthOrderInfoListBO authOrderInfoListBO) {
        if (StringUtils.isEmpty(authOrderInfoListBO.getLecturerUserNo())) {
            return Result.error("lecturerUserNo?????????");
        }
        OrderInfoExample example = new OrderInfoExample();
        Criteria c = example.createCriteria();
        c.andLecturerUserNoEqualTo(authOrderInfoListBO.getLecturerUserNo());
        c.andIsShowUserEqualTo(IsShowUserEnum.YES.getCode());
        c.andPricePaidGreaterThanOrEqualTo(BigDecimal.valueOf(0.5));
        // ?????????????????????????????????
        c.andOrderStatusEqualTo(OrderStatusEnum.SUCCESS.getCode());
        example.setOrderByClause(" id desc ");
        Page<OrderInfo> page = orderInfoDao.listForPage(authOrderInfoListBO.getPageCurrent(), authOrderInfoListBO.getPageSize(), example);
        Page<AuthOrderInfoListForLecturerDTO> dtoPage = PageUtil.transform(page, AuthOrderInfoListForLecturerDTO.class);
        for (AuthOrderInfoListForLecturerDTO dto : dtoPage.getList()) {
            dto.setPhone(dto.getMobile().substring(0, 3) + "****" + dto.getMobile().substring(7, dto.getMobile().length()));
        }
        return Result.success(dtoPage);
    }

    /**
     * ?????????????????????
     *
     * @param authOrderInfoForChartsBO
     * @return
     */
    public Result<Option> charts(AuthOrderInfoForChartsBO authOrderInfoForChartsBO) {
        Option option = new Option();
        option.legend().data("????????????", "????????????");
        option.tooltip().trigger(Trigger.axis).axisPointer();
        option.calculable(true);
        // ??????x?????????
        CategoryAxis categoryAxis = new CategoryAxis();
        List<String> xData = new ArrayList<>();
        payTime(authOrderInfoForChartsBO, xData);
        for (String x : xData) {
            categoryAxis.data(x);
        }
        option.xAxis(categoryAxis);

        // ??????y?????????
        ValueAxis valueAxis = new ValueAxis();
        valueAxis.type(AxisType.value);
        valueAxis.splitArea().show(true);
        valueAxis.axisLabel().formatter("{value}???");
        option.yAxis(valueAxis);
        // ???????????????????????????
        Line line1 = new Line();
        List<AuthOrderInfoLecturerIncomeDTO> dtoList = sumByLecturerUserNoAndData(authOrderInfoForChartsBO.getLecturerUserNo(), xData);
        for (AuthOrderInfoLecturerIncomeDTO dto : dtoList) {
            for (BigDecimal bi : dto.getLecturerProfit()) {
                line1.data(bi);
            }
        }
        line1.name("??????	");
        option.series(line1);
        option.toolbox().show(true).feature(new MagicType(Magic.line, Magic.bar), Tool.restore, Tool.saveAsImage);
        return Result.success(option);
    }

    private List<AuthOrderInfoLecturerIncomeDTO> sumByLecturerUserNoAndData(Long lecturerUserNo, List<String> xData) {
        List<AuthOrderInfoLecturerIncomeDTO> list = new ArrayList<>();
        AuthOrderInfoLecturerIncomeDTO dto = new AuthOrderInfoLecturerIncomeDTO();
        List<BigDecimal> countPaidPrice = new ArrayList<>();
        for (String date : xData) {
            BigDecimal sum = orderInfoDao.sumLecturerUserNoAndData(lecturerUserNo, date);
            countPaidPrice.add(sum);
        }
        dto.setLecturerProfit(countPaidPrice);
        list.add(dto);
        return list;
    }

    private List<String> payTime(AuthOrderInfoForChartsBO authOrderInfoForChartsBO, List<String> xData) {
        // ??????????????????????????????????????????????????????????????????
        if (authOrderInfoForChartsBO.getBeginCreate() == null && authOrderInfoForChartsBO.getEndCreate() == null) {
            authOrderInfoForChartsBO.setBeginCreate(DateUtil.format(DateUtil.addDate(new Date(), -7)));
            authOrderInfoForChartsBO.setEndCreate(DateUtil.format(new Date()));
        }
        Calendar tempStart = Calendar.getInstance();
        tempStart.setTime(DateUtil.parseDate(authOrderInfoForChartsBO.getBeginCreate(), "yyyy-MM-dd"));
        tempStart.add(Calendar.DAY_OF_YEAR, 0);
        Calendar tempEnd = Calendar.getInstance();
        tempEnd.setTime(DateUtil.parseDate(authOrderInfoForChartsBO.getEndCreate(), "yyyy-MM-dd"));
        tempEnd.add(Calendar.DAY_OF_YEAR, 1);
        while (tempStart.before(tempEnd)) {
            xData.add(DateUtil.formatDate(tempStart.getTime()));
            tempStart.add(Calendar.DAY_OF_YEAR, 1);
        }
        return xData;
    }

    /**
     * ??????????????????????????????
     *
     * @param orderInfoPayBO
     */
    private void verifyParam(AuthOrderPayBO authOrderPayBO) {
        if (StringUtils.isEmpty(authOrderPayBO.getUserNo())) {
            throw new BaseException("userNo????????????");
        }
        if (StringUtils.isEmpty(authOrderPayBO.getCourseId())) {
            throw new BaseException("courseId????????????");
        }
        if (StringUtils.isEmpty(authOrderPayBO.getPayType())) {
            throw new BaseException("payType????????????");
        }
        if (StringUtils.isEmpty(authOrderPayBO.getChannelType())) {
            throw new BaseException("channelType????????????");
        }
    }

    /**
     * ??????????????????????????????
     */
    private boolean checkOrderInfo(long userNo, long courseId) {
        OrderInfo orderInfo = orderInfoDao.getByUserNoAndCourseId(userNo, courseId);
        if (ObjectUtil.isNull(orderInfo)) {
            return true;
        } else if (!OrderStatusEnum.SUCCESS.getCode().equals(orderInfo.getOrderStatus())) {
            return true;
        }
        return false;
    }

    /**
     * ????????????????????????
     */
    private OrderPay createOrderPay(OrderInfo retrunOrderInfo) {
        OrderPay orderpay = new OrderPay();
        orderpay.setOrderNo(retrunOrderInfo.getOrderNo());
        orderpay.setOrderStatus(retrunOrderInfo.getOrderStatus());
        orderpay.setPayType(retrunOrderInfo.getPayType());
        orderpay.setSerialNumber(NOUtil.getSerialNumber());
        orderPayDao.save(orderpay);
        return orderpay;
    }

    /**
     * ?????????????????????
     */
    private OrderInfo createOrderInfo(AuthOrderPayBO authOrderPayBO, Course course, UserExtVO userextvo, LecturerVO lecturervo) {
        OrderInfo orderInfo = new OrderInfo();
        orderInfo.setCourseName(course.getCourseName());
        orderInfo.setCourseId(course.getId());
        orderInfo.setPricePaid(course.getCourseDiscount());
        orderInfo.setPricePayable(course.getCourseOriginal());
        orderInfo.setLecturerUserNo(lecturervo.getLecturerUserNo());
        orderInfo.setLecturerName(lecturervo.getLecturerName());
        orderInfo.setUserNo(userextvo.getUserNo());
        orderInfo.setMobile(userextvo.getMobile());
        orderInfo.setRegisterTime(userextvo.getGmtCreate());
        orderInfo.setOrderNo(NOUtil.getOrderNo()); // ????????????????????????IdWorker??????
        orderInfo.setCourseId(course.getId());
        orderInfo.setCourseName(course.getCourseName());
        orderInfo.setPriceDiscount(BigDecimal.ZERO);
        orderInfo.setPlatformProfit(BigDecimal.ZERO);
        orderInfo.setLecturerProfit(BigDecimal.ZERO);
        orderInfo.setIsShowUser(IsShowUserEnum.YES.getCode());
        orderInfo.setTradeType(TradeTypeEnum.ONLINE.getCode());
        orderInfo.setPayType(authOrderPayBO.getPayType());
        orderInfo.setChannelType(authOrderPayBO.getChannelType());
        orderInfo.setRemarkCus(authOrderPayBO.getRemarkCus());
        orderInfo.setOrderStatus(OrderStatusEnum.WAIT.getCode());
        orderInfoDao.save(orderInfo);
        return orderInfo;
    }

    class updateCount implements Runnable {
        private Course course;

        public updateCount(Course course) {
            this.course = course;
        }

        @Override
        public void run() {
            Course info = new Course();
            info.setCountBuy(course.getCountBuy() + 1);
            info.setId(course.getId());
            courseDao.updateById(course);
        }
    }

}
