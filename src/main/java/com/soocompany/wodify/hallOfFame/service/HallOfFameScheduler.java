package com.soocompany.wodify.hallOfFame.service;

import com.soocompany.wodify.box.domain.Box;
import com.soocompany.wodify.box.repository.BoxRepository;
import com.soocompany.wodify.hallOfFame.domain.HallOfFame;
import com.soocompany.wodify.hallOfFame.repository.HallOfFameRepository;
import com.soocompany.wodify.member.domain.Member;
import com.soocompany.wodify.member.domain.Role;
import com.soocompany.wodify.member.repository.MemberRepository;
import com.soocompany.wodify.reservation.domain.Reservation;
import com.soocompany.wodify.reservation.repository.ReservationRepository;
import com.soocompany.wodify.reservation_detail.controller.SseController;
import com.soocompany.wodify.reservation_detail.domain.ReservationDetail;
import com.soocompany.wodify.reservation_detail.repository.ReservationDetailRepository;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Component
@Slf4j
public class HallOfFameScheduler {
    private final BoxRepository boxRepository;
    private final MemberRepository memberRepository;
    private final HallOfFameRepository hallOfFameRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationDetailRepository reservationDetailRepository;
    private final SseController sseController;



    private final RedissonClient redissonClient;
    public HallOfFameScheduler(BoxRepository boxRepository,  SseController sseController, ReservationDetailRepository reservationDetailRepository, ReservationRepository reservationRepository, HallOfFameRepository hallOfFameRepository, MemberRepository memberRepository, RedissonClient redissonClient) {
        this.boxRepository = boxRepository;
        this.sseController = sseController;
        this.reservationDetailRepository = reservationDetailRepository;
        this.reservationRepository = reservationRepository;
        this.hallOfFameRepository = hallOfFameRepository;
        this.memberRepository = memberRepository;
        this.redissonClient = redissonClient;
    }






    //    @Scheduled(cron = "0 0 0 * * *")  //자정
    @Scheduled(cron = "0 * * * * *")

    @Transactional
    public void updateHallOfFame() throws InterruptedException{

        RLock lock = redissonClient.getLock("hallOfFameLock");
        try{
            boolean isLocked = lock.tryLock(2, 3, TimeUnit.SECONDS);
            if(!isLocked){
                log.info("명예의 전당 : 다른 서버");
            }
            log.info("명예의 전당 : 현재 서버");
            for(Box box : boxRepository.findByDelYn("N")){
                //각 박스의 멤버 리스트
                List<Member> memberList = memberRepository.findByBoxAndRoleAndDelYn(box, Role.USER, "N");
                LocalDate yesterday = LocalDate.now().minusDays(1);
                //어제의 예약들 (여러 시간대. 와드는 모두 동일)
                List<Reservation> reservationList = reservationRepository.findAllByBoxAndDateAndDelYn(box, yesterday, "N");
                List<HallOfFame> hallOfFameList = new ArrayList<>();
                for(Reservation r : reservationList){
                    List<ReservationDetail> reservationDetailList = reservationDetailRepository.findByReservationAndDelYn(r, "N");
                    for(ReservationDetail rd : reservationDetailList){
                        if(rd.getRecord() != null && rd.getRecord().getSnf().equals("S")){
                            HallOfFame hallOfFame = HallOfFame.builder()
                                    .member(rd.getMember())
                                    .box(box)
                                    .exerciseTime(rd.getRecord().getExerciseTime())
                                    .build();
                            hallOfFameList.add(hallOfFame);
                        }

                    }
                }

                hallOfFameList = hallOfFameList.stream().sorted(Comparator.comparing(HallOfFame::getExerciseTime))
                        .limit(5)
                        .collect(Collectors.toList());

                for(HallOfFame hof: hallOfFameList){
                    if(hallOfFameRepository.findByMember(hof.getMember()).isEmpty()){
//                            System.out.println("명예의 전당");
                        sseController.publishHallOfFameMessage(String.valueOf(hof.getMember().getId()));
                    }
                }

                //순위 갱신
                hallOfFameRepository.deleteByBox(box);
                int rank = 1;
                for(HallOfFame hallOfFame : hallOfFameList){
                    hallOfFameRepository.save(HallOfFame.builder()
                            .member(hallOfFame.getMember())
                            .box(box)
                            .rank(rank++)
                            .exerciseTime(hallOfFame.getExerciseTime())
                            .build());
                }

            }

        }catch(InterruptedException e){
            throw new RuntimeException("명예의 전당 스케줄러 오류"+e.getMessage());
        }finally{
            lock.unlock();
        }

    }
}
