package org.leisureup.location.internal.service;

import java.time.*;
import java.util.*;
import lombok.*;
import lombok.extern.slf4j.*;
import org.leisureup.location.internal.domain.*;
import org.leisureup.location.internal.dto.api.*;
import org.leisureup.location.internal.dto.event.*;
import org.leisureup.location.internal.repository.*;
import org.springframework.context.*;
import org.springframework.context.event.EventListener;
import org.springframework.modulith.events.*;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.*;
import org.springframework.transaction.annotation.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class DataSyncEventHandler {

    private final TourApiService tourApiService;
    private final LocationRepository locationRepo;
    private final CommonInfoCacheRepository commonInfoCacheRepo;
    private final ApplicationEventPublisher eventPublisher;

    private static void changeEntityInfo(Location location, CommonInfo info) {
        location.changeTitle(info.title());
        location.changeCord(info.gpsCord());
        location.changeAddress(info.address());
        location.changeDescription(info.locationDescription());
        location.synchronizeTo(info.modifiedTime());
    }

    /**
     * 해당 장소가 API 상 수정되었는지 확인하는 method
     * <li>
     * 만약 정보가 수정 되었다면 : {@link #handleDataSyncEvent}
     * <br>
     * 장소 정보를 update 하는 이벤트 {@code (DataSyncEvent)} 발행
     * </li>
     *
     * <br>
     *
     * <li>
     * 만약 정보가 그대로라면 : {@link #handleUpdateLastSyncTimeEvent}
     * <br>
     * 마지막 확인 기록만 update 하는 이벤트 {@code (UpdateLastSyncTimeEvent)} 발행
     * </li>
     *
     * @param event 장소 수정이 필요한지 확인하는 event
     * @see #handleDataSyncEvent
     * @see #handleUpdateLastSyncTimeEvent
     */
    @Async
    @EventListener(DataSyncCheckEvent.class)
    public void handleSyncCheckEvent(DataSyncCheckEvent event) {

        Long locationId = event.locationId();
        LocalDateTime lastModifiedTimeOnDb = event.lastModifiedAt();

        log.info("Data check event for location [{}] has been received", locationId);

        CommonInfo fetchedInfo = tourApiService.getCommonInfo(locationId);
        Object nextEvent;

        if (lastModifiedTimeOnDb.isAfter(fetchedInfo.modifiedTime())) {
            log.info("Location [{}] is up-to-date. Publishing update sync time event.", locationId);
            nextEvent = new UpdateLastSyncTimeEvent(locationId);
        } else {
            log.info("Location [{}] is out-of-date. Publishing data sync event.", locationId);
            commonInfoCacheRepo.save(CommonInfoCache.of(locationId, fetchedInfo));
            nextEvent = new DataSyncEvent(locationId);
        }

        eventPublisher.publishEvent(nextEvent);
    }

    /**
     * 해당 장소의 마지막 동기화 기록만 update 하는 method
     */
    @ApplicationModuleListener(propagation = Propagation.REQUIRES_NEW)
    public void handleUpdateLastSyncTimeEvent(UpdateLastSyncTimeEvent event) {

        Long locationId = event.locationId();

        log.info("Update last sync time event for location [{}] has been received", locationId);

        locationRepo.updateModifiedTimeFor(locationId);

        log.info("Updated last syn time.");     // 사실 벌크성 jpql 도 tx 커밋되야 반영되긴 한다.
    }

    /**
     * 해당 장소의 정보를 update 하는 method
     */
    @ApplicationModuleListener(propagation = Propagation.REQUIRES_NEW)
    public void handleDataSyncEvent(DataSyncEvent event) {

        Long locationId = event.locationId();

        log.info("Data sync event for location [{}] has been received", locationId);

        // DB 저장된 장소를 가져온다.
        Location target = locationRepo.findById(locationId)
                .orElseThrow(() -> {
                    String msg = String.format(
                            "Unable to find location with id [%s], fatal error",
                            locationId
                    );
                    log.error(msg);
                    return new RuntimeException(msg);
                });

        CommonInfo replace;

        // 수정할 정보가 cache 에 남아있으면 가져오고
        Optional<CommonInfoCache> cache = commonInfoCacheRepo.findById(locationId);

        if (cache.isPresent()) {
            replace = cache.get().getInfo();
            commonInfoCacheRepo.delete(cache.get());
        } else {
            // 없으면 API 써서 가져온다.
            replace = tourApiService.getCommonInfo(locationId);
        }

        // 정보를 수정한다.
        changeEntityInfo(target, replace);

        log.info("Data will be changed after transaction commit.");
    }
}
