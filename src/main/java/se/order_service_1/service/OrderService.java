package se.order_service_1.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import se.order_service_1.client.ReservationClient;
import se.order_service_1.dto.*;
import se.order_service_1.exception.OrderCompletedException;
import se.order_service_1.exception.OrderNotFoundException;
import se.order_service_1.model.OrderItem;
import se.order_service_1.model.Order;
import se.order_service_1.repository.OrderItemRepository;
import se.order_service_1.repository.OrderRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@RequiredArgsConstructor
@Service
public class OrderService {
    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final PaymentService paymentService;
    private final RestTemplate restTemplate;
    private final ReservationClient reservationClient;

    @Value("${product.service.address}")
    private String productServiceAddress;

    @Transactional
    public void addOrderItem(Long orderId, Long productId, int quantity) {
        log.debug("addOrderItem - försök lägga till produkt {} (qty={}) till orderId={}",
                productId, quantity, orderId);

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> {
                    log.error("addOrderItem - Order med id={} hittades inte", orderId);
                    return new OrderNotFoundException("Order med ID " + orderId + " finns inte");
                });

        checkNotCompleted(order);

        List<OrderItem> items = orderItemRepository.findByOrderId(orderId);
        log.debug("addOrderItem - orderId={} har {} items före uppdatering", orderId, items.size());

        // Skapa en reservation för produkten
        reserveProductsForOrder(orderId, productId, quantity);

        for(OrderItem item : items){
            if(item.getProductId().equals(productId)){
                log.info("addOrderItem - produkt {} finns redan i orderId={}, uppdaterar qty {} -> {}",
                        productId, orderId, item.getQuantity(), item.getQuantity() + quantity);

                item.setQuantity(item.getQuantity() + quantity);
                orderItemRepository.save(item);
                log.debug("addOrderItem - orderId={} sparad efter uppdatering", orderId);
                return;
            }
        }

        OrderItem newItem = new OrderItem();
        newItem.setProductId(productId);
        newItem.setOrder(order);
        newItem.setQuantity(quantity);
        orderItemRepository.save(newItem);

        log.info("addOrderItem - produkt {} tillagd i orderId={} med qty={}",
                newItem.getProductId(), orderId, quantity);

        log.debug("addOrderItem - orderId={} sparad med nytt item, total items={}", orderId, items.size());
    }

    private void reserveProductsForOrder(Long orderId, Long productId, int quantity) {
        // Skapa en reservation för produkten
        List<ProductReservation> productReservations = new ArrayList<>();
        productReservations.add(ProductReservation.builder()
                .productId(productId)
                .quantity(quantity)
                .build());

        ReservationRequest request = ReservationRequest.builder()
                .orderId(orderId)
                .productReservations(productReservations)
                .build();

        reservationClient.reserveProducts(request);
    }

    public List<OrderItem> getOrderItems(Long orderId) {
        List<OrderItem> items = orderItemRepository.findByOrderId(orderId);
        return items;
    }

    public List<Order> getAllOrders() {
        log.debug("getAllOrders - hämta alla ordrar");
        List<Order> orders = orderRepository.findAll();
        log.debug("getAllOrders - antal ordrar={}", orders.size());
        return orders;
    }

    @Transactional
    public void deleteOrder(Long orderId) {
        log.info("deleteOrder - försök radera order med id={}", orderId);
        if(orderRepository.existsById(orderId)){
            Order order = getOrderById(orderId);
            checkNotCompleted(order);

            // Avbryt reservationerna först
            reservationClient.cancelReservations(orderId);

            List<OrderItem> items = orderItemRepository.findByOrderId(orderId);
            orderItemRepository.deleteAll(items);
            orderRepository.deleteById(orderId);
            log.info("deleteOrder - order raderad med id={}", orderId);
        } else {
            log.warn("deleteOrder - ingen order att radera med id={}", orderId);
        }
    }

    public Order getOrderById(Long orderId) {
        log.debug("getOrderById - försök hämta order med id={}", orderId);
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> {
                    log.warn("getOrderById - ingen order hittades med id={}", orderId);
                    return new OrderNotFoundException("Order med ID " + orderId + " finns inte");
                });
        log.debug("getOrderById - hittade order={}", orderId);
        return order;
    }

    public List<Order> getOrdersByUser(Long userId) {
        log.debug("getOrdersByUser - försök hämta alla ordrar från använder med id={}", userId);
        List <Order> orders = orderRepository.findOrdersByUserId(userId);
        return orders;
    }

    @Transactional
    public Order updateOrder(Long orderID, Long productID, Integer quantity) {
        Order order = getOrderById(orderID);
        checkNotCompleted(order);

        List<OrderItem> orderItems = getOrderItems(orderID);

        // Om kvantiteten är positiv, uppdatera eller lägg till
        if (quantity > 0) {
            for(OrderItem orderItem : orderItems){
                if(orderItem.getProductId().equals(productID)){
                    // Beräkna differensen
                    int diff = quantity - orderItem.getQuantity();

                    // Om differensen är positiv, vi behöver reservera mer
                    if (diff > 0) {
                        reserveProductsForOrder(orderID, productID, diff);
                    }
                    // Om differensen är negativ, vi behöver frigöra några
                    else if (diff < 0) {
                        // Avbryt reservation för differensen (omvandla till positivt tal)
                        reservationClient.cancelProductReservation(orderID, productID, -diff);
                    }

                    orderItem.setQuantity(quantity);
                    orderItemRepository.save(orderItem);
                    return order;
                }
            }

            // Om produkten inte finns i ordern, lägg till den
            addOrderItem(orderID, productID, quantity);
        }
        // Om kvantiteten är 0 eller mindre, ta bort orderItem
        else {
            for(OrderItem orderItem : orderItems){
                if(orderItem.getProductId().equals(productID)){
                    // Avbryt reservation för denna produkt
                    reservationClient.cancelProductReservation(orderID, productID, orderItem.getQuantity());

                    // Ta bort orderItem
                    orderItemRepository.delete(orderItem);
                    break;
                }
            }
        }

        return order;
    }

    /**
     * Slutför en order genom att behandla betalning och ändra orderstatus
     * @param orderId ID för ordern som ska slutföras
     * @param paymentRequest betalningsuppgifter
     * @return transaktions-ID för betalningen
     */
    @Transactional
    public String finalizeOrder(Long orderId, PaymentRequest paymentRequest) {
        log.info("finalizeOrder - försök slutföra order med id={}", orderId);

        Order order = getOrderById(orderId);
        checkNotCompleted(order);

        // Bekräfta reservationer i ProductService
        reservationClient.confirmReservations(orderId);

        // Beräkna ordersumma
        Double totalBelopp = calculateOrderTotal(orderId);

        // Uppdatera betalningsbeloppet
        paymentRequest.setBelopp(totalBelopp);

        // Behandla betalning
        String transactionId = paymentService.processPayment(paymentRequest);

        // Uppdatera orderstatus
        order.setOrderStatus(Order.OrderStatus.COMPLETED);
        order.setOrderDate(LocalDateTime.now());
        orderRepository.save(order);

        log.info("finalizeOrder - order {} slutförd med transaktions-ID: {}", orderId, transactionId);

        return transactionId;
    }

    public List<Order> getOrdersAfterOrderDate(Long userId, LocalDateTime orderDate) {
        return orderRepository.findByUserIdAndOrderDateAfter(userId, orderDate);
    }

    /**
     * Beräknar totalsumman för en order
     * OBS: I ett riktigt system skulle detta hämta produktpriser från Product Service
     */
    private Double calculateOrderTotal(Long orderId) {
        List<OrderItem> orderItems = getOrderItems(orderId);

        // Låtsas att varje produkt kostar 100 kr
        // I ett riktigt system skulle vi hämta aktuella priser från Product Service
        double standardPrisPerProdukt = 100.0;

        double total = orderItems.stream()
                .mapToDouble(item -> item.getQuantity() * standardPrisPerProdukt)
                .sum();

        log.debug("calculateOrderTotal - beräknad summa för order {}: {} kr", orderId, total);

        return total;
    }

    // Temporär funktion för testning
    @Transactional
    public Order createOrder(Long userId){
        Order order = Order.builder()
                .userId(userId)
                .orderStatus(Order.OrderStatus.ONGOING)
                .build();
        return orderRepository.save(order);
    }

    private void checkNotCompleted(Order order) {
        if (order.getOrderStatus() == Order.OrderStatus.COMPLETED) {
            throw new OrderCompletedException("Order is completed and can not be changed or cancelled");
        }
    }
}