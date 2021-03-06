package pl.tomaszdziurko.jvm_bloggers.view.login.attack.stream

import org.springframework.test.util.ReflectionTestUtils
import pl.tomaszdziurko.jvm_bloggers.mailing.LogMailSender
import pl.tomaszdziurko.jvm_bloggers.mailing.LogMailSenderPostAction
import pl.tomaszdziurko.jvm_bloggers.metadata.Metadata
import pl.tomaszdziurko.jvm_bloggers.metadata.MetadataKeys
import pl.tomaszdziurko.jvm_bloggers.metadata.MetadataRepository
import pl.tomaszdziurko.jvm_bloggers.view.login.attack.BruteForceAttackEvent
import pl.tomaszdziurko.jvm_bloggers.view.login.attack.BruteForceAttackMailGenerator
import rx.Scheduler
import rx.schedulers.TestScheduler
import spock.lang.Specification

import java.util.concurrent.TimeUnit

import static BruteForceAttackEventStreamManager.MAILING_TIME_THROTTLE_IN_MINUTES

class BruteForceAttackEventStreamManagerSpec extends Specification {

    LogMailSender mailSender;
    LogMailSenderPostAction logMailPostAction;
    Scheduler scheduler;
    MetadataRepository metadataRepository;
    BruteForceAttackEventStreamManager manager;

    def setup() {
        manager = new BruteForceAttackEventStreamManager(
            mailSender = new LogMailSender(logMailPostAction = new LogMailSenderPostAction()),
            Mock(BruteForceAttackMailGenerator) {
                prepareMailTitle(_) >> "mail title"
                prepareMailContent(_) >> "mail content"
            },
            scheduler = new TestScheduler(),
            metadataRepository = Mock(MetadataRepository))
    }

    def "Should throw Runtime exception when ADMIN_EMAIL metadata is not found"() {
        given:
            metadataRepository.findByName(_) >> null
        when:
            manager.init()
        then:
            RuntimeException ex = thrown()
            ex.message.equals(MetadataKeys.ADMIN_EMAIL + " not found in Metadata table")
    }

    def "Should return admin email"() {
        given:
            metadataRepository.findByName(_) >> new Metadata(MetadataKeys.ADMIN_EMAIL, "admin@jvmbloggers.pl")
        when:
            manager.init()
        then:
            ReflectionTestUtils.getField(manager, "adminEmailAddress") == "admin@jvmbloggers.pl"
    }

    def "Should build BruteForceAttackEventStream for given clientAddress only once"() {
        given:
            String clientAddress = "127.0.0.1"
        when:
            BruteForceAttackEventStream eventStream = manager.createEventStreamFor(clientAddress)
        then:
            eventStream == manager.createEventStreamFor(clientAddress)
    }

    def "Should send an email"() {
        given:
            logMailPostAction.actionsToWaitOn(1)
            String clientAddress = "127.0.0.1"
            BruteForceAttackEventStream eventStream = manager.createEventStreamFor(clientAddress)
        when:
            eventStream.publish(BruteForceAttackEvent.builder().ipAddress(clientAddress).build());
            scheduler.advanceTimeTo(MAILING_TIME_THROTTLE_IN_MINUTES, TimeUnit.MINUTES);
        then:
            mailSender.getLogMailSenderPostAction().awaitActions();
    }

    def "Should send an email only three times"() {
        given:
            logMailPostAction.actionsToWaitOn(3)
            String clientAddress = "127.0.0.1"
            BruteForceAttackEventStream eventStream = manager.createEventStreamFor(clientAddress)
            int counter = 1
        when:
            (1..19).each {
                eventStream.publish(BruteForceAttackEvent.builder().ipAddress(clientAddress).build());
                if (it.intValue() % 5 == 0) {
                    scheduler.advanceTimeTo(MAILING_TIME_THROTTLE_IN_MINUTES * (counter++), TimeUnit.MINUTES);
                }
            }
        then:
            mailSender.getLogMailSenderPostAction().awaitActions()
    }

    def "Should terminate all streams on destroy"() {
        given:
            String clientAddress = "127.0.0.1"
            BruteForceAttackEventStream eventStream = manager.createEventStreamFor(clientAddress)
        when:
            manager.destroy();
            scheduler.advanceTimeTo(MAILING_TIME_THROTTLE_IN_MINUTES, TimeUnit.MINUTES);
        then:
            eventStream.isTerminated()
    }
}
