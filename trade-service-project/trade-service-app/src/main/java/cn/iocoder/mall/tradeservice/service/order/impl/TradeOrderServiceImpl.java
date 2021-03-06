package cn.iocoder.mall.tradeservice.service.order.impl;

import cn.iocoder.common.framework.exception.util.ServiceExceptionUtil;
import cn.iocoder.common.framework.util.CollectionUtils;
import cn.iocoder.common.framework.util.DateUtil;
import cn.iocoder.common.framework.util.MathUtil;
import cn.iocoder.common.framework.vo.PageResult;
import cn.iocoder.mall.payservice.rpc.transaction.dto.PayTransactionCreateReqDTO;
import cn.iocoder.mall.productservice.enums.sku.ProductSkuDetailFieldEnum;
import cn.iocoder.mall.productservice.rpc.sku.dto.ProductSkuRespDTO;
import cn.iocoder.mall.promotion.api.rpc.price.dto.PriceProductCalcReqDTO;
import cn.iocoder.mall.promotion.api.rpc.price.dto.PriceProductCalcRespDTO;
import cn.iocoder.mall.tradeservice.client.pay.PayTransactionClient;
import cn.iocoder.mall.tradeservice.client.product.ProductSkuClient;
import cn.iocoder.mall.tradeservice.client.promotion.CouponCardClient;
import cn.iocoder.mall.tradeservice.client.promotion.PriceClient;
import cn.iocoder.mall.tradeservice.client.user.UserAddressClient;
import cn.iocoder.mall.tradeservice.config.TradeBizProperties;
import cn.iocoder.mall.tradeservice.convert.order.TradeOrderConvert;
import cn.iocoder.mall.tradeservice.dal.mysql.dataobject.order.TradeOrderDO;
import cn.iocoder.mall.tradeservice.dal.mysql.dataobject.order.TradeOrderItemDO;
import cn.iocoder.mall.tradeservice.dal.mysql.mapper.order.TradeOrderItemMapper;
import cn.iocoder.mall.tradeservice.dal.mysql.mapper.order.TradeOrderMapper;
import cn.iocoder.mall.tradeservice.enums.logistics.LogisticsDeliveryTypeEnum;
import cn.iocoder.mall.tradeservice.enums.order.TradeOrderAfterSaleStatusEnum;
import cn.iocoder.mall.tradeservice.enums.order.TradeOrderDetailFieldEnum;
import cn.iocoder.mall.tradeservice.enums.order.TradeOrderStatusEnum;
import cn.iocoder.mall.tradeservice.rpc.order.dto.TradeOrderCreateReqDTO;
import cn.iocoder.mall.tradeservice.rpc.order.dto.TradeOrderPageReqDTO;
import cn.iocoder.mall.tradeservice.rpc.order.dto.TradeOrderRespDTO;
import cn.iocoder.mall.tradeservice.service.order.TradeOrderService;
import cn.iocoder.mall.userservice.rpc.address.dto.UserAddressRespDTO;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

import static cn.iocoder.common.framework.util.CollectionUtils.convertSet;
import static cn.iocoder.mall.tradeservice.enums.OrderErrorCodeConstants.*;
import static cn.iocoder.mall.userservice.enums.UserErrorCodeConstants.USER_ADDRESS_NOT_FOUND;

/**
 * ???????????? Service ??????
 */
@Service
public class TradeOrderServiceImpl implements TradeOrderService {

    @Autowired
    private TradeOrderMapper tradeOrderMapper;
    @Autowired
    private TradeOrderItemMapper tradeOrderItemMapper;

    @Autowired // ???????????????????????????????????????
    private TradeOrderServiceImpl self;

    @Autowired
    private UserAddressClient userAddressClient;
    @Autowired
    private ProductSkuClient productSkuClient;
    @Autowired
    private PriceClient priceClient;
    @Autowired
    private CouponCardClient couponCardClient;
    @Autowired
    private PayTransactionClient payTransactionClient;

    @Autowired
    private TradeBizProperties tradeBizProperties;

    @Override
//    @GlobalTransactional TODO ??????????????? seata ?????????????????????
    public Integer createTradeOrder(TradeOrderCreateReqDTO createReqDTO) {
        // ??????????????????
        UserAddressRespDTO userAddressRespDTO = userAddressClient.getUserAddress(createReqDTO.getUserAddressId(),
                createReqDTO.getUserId());
        if (userAddressRespDTO == null) {
            throw ServiceExceptionUtil.exception(USER_ADDRESS_NOT_FOUND);
        }
        // ??????????????????
        List<ProductSkuRespDTO> listProductSkus = productSkuClient.listProductSkus(
                convertSet(createReqDTO.getOrderItems(), TradeOrderCreateReqDTO.OrderItem::getSkuId),
                ProductSkuDetailFieldEnum.SPU.getField());
        if (listProductSkus.size() != createReqDTO.getOrderItems().size()) { // ????????????????????????????????????
            throw ServiceExceptionUtil.exception(ORDER_GET_GOODS_INFO_INCORRECT);
        }
        // ????????????
        PriceProductCalcRespDTO priceProductCalcRespDTO = priceClient.calcProductPrice(createReqDTO.getUserId(),
                createReqDTO.getOrderItems().stream().map(orderItem -> new PriceProductCalcReqDTO.Item().setSkuId(orderItem.getSkuId())
                        .setQuantity(orderItem.getQuantity()).setSelected(true)).collect(Collectors.toList()),
                createReqDTO.getCouponCardId());

        // TODO ?????????????????????

        // ????????????????????????
        if (createReqDTO.getCouponCardId() != null) {
            couponCardClient.useCouponCard(createReqDTO.getUserId(), createReqDTO.getCouponCardId());
        }

        // ????????????????????????????????????
        TradeOrderDO tradeOrderDO = self.createTradeOrder0(createReqDTO, listProductSkus, priceProductCalcRespDTO, userAddressRespDTO);

        // ???????????????????????????????????????
        createPayTransaction(tradeOrderDO, createReqDTO, listProductSkus);
        return tradeOrderDO.getId();
    }

    @Transactional
    public TradeOrderDO createTradeOrder0(TradeOrderCreateReqDTO createReqDTO, List<ProductSkuRespDTO> listProductSkus,
                                     PriceProductCalcRespDTO priceProductCalcRespDTO, UserAddressRespDTO userAddressRespDTO) {
        // ?????? TradeOrderDO ????????????????????????
        TradeOrderDO tradeOrderDO = new TradeOrderDO();
        // 1. ????????????
        tradeOrderDO.setUserId(createReqDTO.getUserId()).setOrderNo(generateTradeOrderNo())
                .setOrderStatus(TradeOrderStatusEnum.WAITING_PAYMENT.getValue()).setRemark(createReqDTO.getRemark());
        // 2. ?????? + ??????????????????
        tradeOrderDO.setBuyPrice(priceProductCalcRespDTO.getFee().getBuyTotal())
                .setDiscountPrice(priceProductCalcRespDTO.getFee().getDiscountTotal())
                .setLogisticsPrice(priceProductCalcRespDTO.getFee().getPostageTotal())
                .setPresentPrice(priceProductCalcRespDTO.getFee().getPresentTotal())
                .setPayPrice(0).setRefundPrice(0);
        // 3. ?????? + ??????????????????
        tradeOrderDO.setDeliveryType(LogisticsDeliveryTypeEnum.EXPRESS.getDeliveryType())
                .setReceiverName(userAddressRespDTO.getName()).setReceiverMobile(userAddressRespDTO.getMobile())
                .setReceiverAreaCode(userAddressRespDTO.getAreaCode()).setReceiverDetailAddress(userAddressRespDTO.getDetailAddress());
        // 4. ??????????????????
        tradeOrderDO.setAfterSaleStatus(TradeOrderAfterSaleStatusEnum.NULL.getStatus());
        // 5. ??????????????????
        tradeOrderDO.setCouponCardId(createReqDTO.getCouponCardId());
        // ????????????
        tradeOrderMapper.insert(tradeOrderDO);

        // ?????? TradeOrderItemDO ????????????????????????
        List<TradeOrderItemDO> tradeOrderItemDOs = new ArrayList<>(listProductSkus.size());
        Map<Integer, ProductSkuRespDTO> listProductSkuMap = CollectionUtils.convertMap(listProductSkus, ProductSkuRespDTO::getId);
        Map<Integer, PriceProductCalcRespDTO.Item> priceItemMap = new HashMap<>(); // ?????? SKU ???????????????
        priceProductCalcRespDTO.getItemGroups().forEach(itemGroup ->
                itemGroup.getItems().forEach(item -> priceItemMap.put(item.getSkuId(), item)));
        for (TradeOrderCreateReqDTO.OrderItem orderItem : createReqDTO.getOrderItems()) {
            TradeOrderItemDO tradeOrderItemDO = new TradeOrderItemDO();
            tradeOrderItemDOs.add(tradeOrderItemDO);
            // 1. ????????????
            tradeOrderItemDO.setOrderId(tradeOrderDO.getId()).setStatus(tradeOrderDO.getOrderStatus());
            // 2. ??????????????????
            ProductSkuRespDTO productSkuRespDTO = listProductSkuMap.get(orderItem.getSkuId());
            tradeOrderItemDO.setSpuId(productSkuRespDTO.getSpuId()).setSkuId(productSkuRespDTO.getId())
                    .setSkuName(productSkuRespDTO.getSpu().getName())
                    .setSkuImage(CollectionUtils.getFirst(productSkuRespDTO.getSpu().getPicUrls()))
                    .setQuantity(orderItem.getQuantity());
            // 3. ?????? + ??????????????????
            PriceProductCalcRespDTO.Item priceItem = priceItemMap.get(orderItem.getSkuId());
            tradeOrderItemDO.setOriginPrice(priceItem.getOriginPrice()).setBuyPrice(priceItem.getBuyPrice())
                    .setPresentPrice(priceItem.getPresentPrice()).setBuyTotal(priceItem.getBuyTotal())
                    .setDiscountTotal(priceItem.getDiscountTotal()).setPresentTotal(priceItem.getPresentTotal())
                    .setRefundTotal(0);
            // 4. ??????????????????
            // 5. ??????????????????
            tradeOrderItemDO.setAfterSaleStatus(TradeOrderAfterSaleStatusEnum.NULL.getStatus());
        }
        // ????????????
        tradeOrderItemMapper.insertList(tradeOrderItemDOs);

        return tradeOrderDO;
    }

    private void createPayTransaction(TradeOrderDO tradeOrderDO, TradeOrderCreateReqDTO createReqDTO,
                                      List<ProductSkuRespDTO> listProductSkus) {
        // ???????????????
        String orderSubject = listProductSkus.get(0).getSpu().getName();
        Date expireTime = DateUtil.addDate(Calendar.MINUTE, tradeBizProperties.getPayExpireTime());
        Integer payTransactionId = payTransactionClient.createPayTransaction(
                new PayTransactionCreateReqDTO().setUserId(createReqDTO.getUserId())
                        .setCreateIp(createReqDTO.getIp()).setAppId(tradeBizProperties.getPayAppId())
                        .setOrderId(tradeOrderDO.getId().toString()).setExpireTime(expireTime)
                        .setPrice(tradeOrderDO.getPresentPrice()).setOrderSubject(orderSubject)
                        .setOrderMemo("????????????") // TODO ?????????????????????
                        .setOrderDescription("????????????") // TODO ?????????????????????
        );

        // ??????
        tradeOrderMapper.updateById(new TradeOrderDO().setId(tradeOrderDO.getId()).setPayTransactionId(payTransactionId));
    }

    private String generateTradeOrderNo() {
//    wx
//    2014
//    10
//    27
//    20
//    09
//    39
//    5522657
//    a690389285100
        // ???????????????
        // ????????????????????????????????? 14 ???
        // ????????????6 ??? TODO ????????????????????????????????????????????????
        return DateUtil.format(new Date(), "yyyyMMddHHmmss") + // ????????????
                MathUtil.random(100000, 999999) // ????????????????????????????????????????????????
                ;
    }

    @Override
    public TradeOrderRespDTO getTradeOrder(Integer tradeOrderId, Collection<String> fields) {
        // ??????????????????
        TradeOrderDO tradeOrderDO = tradeOrderMapper.selectById(tradeOrderId);
        if (tradeOrderDO == null) {
            return null;
        }
        TradeOrderRespDTO tradeOrderRespDTO = TradeOrderConvert.INSTANCE.convert(tradeOrderDO);
        // ?????????????????????
        if (fields.contains(TradeOrderDetailFieldEnum.ITEM.getField())) {
            List<TradeOrderItemDO> tradeOrderItemDOs = tradeOrderItemMapper.selectListByOrderIds(
                    Collections.singleton(tradeOrderDO.getId()));
            tradeOrderRespDTO.setOrderItems(TradeOrderConvert.INSTANCE.convertList(tradeOrderItemDOs));
        }
        // ??????
        return tradeOrderRespDTO;
    }

    @Override
    public PageResult<TradeOrderRespDTO> pageTradeOrder(TradeOrderPageReqDTO pageReqDTO) {
        // ????????????????????????
        IPage<TradeOrderDO> tradeOrderDOPage = tradeOrderMapper.selectPage(pageReqDTO);
        PageResult<TradeOrderRespDTO> pageResult = TradeOrderConvert.INSTANCE.convertPage(tradeOrderDOPage);
        if (CollectionUtils.isEmpty(pageResult.getList())) {
            return pageResult;
        }
        // ????????????????????????
        if (pageReqDTO.getFields().contains(TradeOrderDetailFieldEnum.ITEM.getField())) {
            List<TradeOrderItemDO> tradeOrderItemDOs = tradeOrderItemMapper.selectListByOrderIds(
                    convertSet(tradeOrderDOPage.getRecords(), TradeOrderDO::getId));
            Map<Integer, List<TradeOrderItemDO>> tradeOrderItemDOMultiMap = CollectionUtils.convertMultiMap(
                    tradeOrderItemDOs, TradeOrderItemDO::getOrderId);
            pageResult.getList().forEach(tradeOrderRespDTO -> tradeOrderRespDTO.setOrderItems(
                    TradeOrderConvert.INSTANCE.convertList(tradeOrderItemDOMultiMap.get(tradeOrderRespDTO.getId()))));
        }
        // ??????
        return pageResult;
    }


    @Override
    @Transactional
    public void updateTradeOrderPaySuccess(Integer tradeOrderId, Integer payAmount) {
//        if (true) {
//            throw new IllegalArgumentException("?????????????????????");
//        }
        // ?????????????????????????????????
        TradeOrderDO tradeOrderDO = tradeOrderMapper.selectById(tradeOrderId);
        if (tradeOrderDO == null) { // ???????????????
            throw ServiceExceptionUtil.exception(ORDER_NOT_EXISTENT);
        }
        if (!tradeOrderDO.getOrderStatus().equals(TradeOrderStatusEnum.WAITING_PAYMENT.getValue())) { // ???????????????????????????
            throw ServiceExceptionUtil.exception(ORDER_STATUS_NOT_WAITING_PAYMENT);
        }
        if (!tradeOrderDO.getPresentPrice().equals(payAmount)) { // ?????????????????????
            throw ServiceExceptionUtil.exception(ORDER_PAY_AMOUNT_ERROR);
        }

        // ?????? TradeOrderDO ?????????????????????????????????
        TradeOrderDO updateOrderObj = new TradeOrderDO().setId(tradeOrderId)
                .setOrderStatus(TradeOrderStatusEnum.WAIT_SHIPMENT.getValue())
                .setPayPrice(payAmount)
                .setPayTime(new Date());
        int updateCount = tradeOrderMapper.update(updateOrderObj, TradeOrderStatusEnum.WAITING_PAYMENT.getValue());
        if (updateCount <= 0) {
            throw ServiceExceptionUtil.exception(ORDER_STATUS_NOT_WAITING_PAYMENT);
        }

        // ?????? TradeOrderItemDO ?????????????????????????????????
        TradeOrderItemDO updateOrderItemObj = new TradeOrderItemDO()
                .setStatus(TradeOrderStatusEnum.WAIT_SHIPMENT.getValue());
        tradeOrderItemMapper.updateListByOrderId(updateOrderItemObj, tradeOrderId,
                TradeOrderStatusEnum.WAITING_PAYMENT.getValue());
    }

}
