package org.leisureup.location.internal.service;

import java.time.*;
import java.util.*;
import lombok.*;
import lombok.extern.slf4j.*;
import org.leisureup.location.internal.domain.*;
import org.leisureup.location.internal.dto.event.*;
import org.leisureup.location.internal.repository.*;
import org.springframework.context.*;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class DataSyncEventScheduler {

    private static final String AT_00_00_AT_EVERY_SUNDAY = "0 0 0 * * 0";
    private final LocationRepository locationRepo;
    private final ApplicationEventPublisher eventPublisher;

    private static LocalDateTime now() {
        return LocalDateTime.now();
    }

    private static LocalDateTime beforeAMonth(LocalDateTime localDateTime) {
        return localDateTime.minusMonths(1);
    }

    private static DataSyncCheckEvent toEvent(Location location) {
        return new DataSyncCheckEvent(
                location.getId(), location.getLastModifiedAt(), location.getLastChangedAt()
        );
    }

    /**
     * 매 일요일 {@code 00:00} 에 발생하는 데이터 확인 스케쥴러
     * <p>
     * DB 에 저장된 장소 중 수정된 지 {@code 오래된} 장소를 탐색하고, 해당 장소 정보가 API 상 수정되었는지 확인한다.
     * <li>오래된 장소별 {@code DataSyncCheckEvent} 이벤트를 발행해
     * {@link DataSyncEventHandler#handleSyncCheckEvent} 에서 확인한다.</li>
     *
     * @see DataSyncEventHandler
     */
    @Scheduled(cron = AT_00_00_AT_EVERY_SUNDAY)
    public void publishCheckEvents() {
        log.info("Data sync event scheduler started");

        // 정보가 수정된 지 오래된 장소를 탐색한다.
        List<Location> syncCandidates = findSyncCandidates();

        log.info("[{}] candidates been found", syncCandidates.size());
        log.info("Publishing data check event.");

        // API 상 장소 정보가 수정되었는지 확인하는 event 를 발행한다.
        syncCandidates.stream()
                .map(DataSyncEventScheduler::toEvent)
                .forEach(eventPublisher::publishEvent);
    }

    private List<Location> findSyncCandidates() {
        // 동기화된지 1 달 넘어간 장소를 확인한다.
        LocalDateTime syncCandidateModifiedTimeThreshold = beforeAMonth(now());
        return locationRepo.findAllLastModifiedBeforeThan(syncCandidateModifiedTimeThreshold);
    }
}

