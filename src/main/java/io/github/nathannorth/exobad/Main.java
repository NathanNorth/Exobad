package io.github.nathannorth.exobad;

import discord4j.common.util.Snowflake;
import discord4j.core.DiscordClientBuilder;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.event.ReactiveEventAdapter;
import discord4j.core.event.domain.VoiceStateUpdateEvent;
import discord4j.core.event.domain.message.ReactionAddEvent;
import discord4j.core.object.entity.Message;
import discord4j.core.object.entity.channel.MessageChannel;
import discord4j.core.object.reaction.ReactionEmoji;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Main {
    private static final ReactionEmoji alarmClock = ReactionEmoji.unicode("\u23F0");

    private static GatewayDiscordClient client = null;
    public static void main(String[] args) {
        client = DiscordClientBuilder.create(getKeys().get(0)).build().login().block();

        Flux<?> celebrations = Flux.interval(Duration.ofSeconds(10))
                .flatMap(num -> Flux.fromIterable(appointments)
                        .filter(a -> Duration.between(Instant.now(), a.time).isNegative())
                        .flatMap(a -> celebrate(a)))
                //throw away errors
                .onErrorResume(e-> {
                    e.printStackTrace();
                    return Mono.empty();
                });

        client.on(eventAdapter).subscribe();
        celebrations.subscribe();

        Mono.never().block();
    }

    //REA handles errors for us
    private static final ReactiveEventAdapter eventAdapter = new ReactiveEventAdapter() {
        @Override
        public Publisher<?> onReactionAdd(ReactionAddEvent event) {
            return Mono.just(event)
                    .filter(reactionAddEvent -> reactionAddEvent.getEmoji().asUnicodeEmoji().isPresent())
                    .filter(reactionEmoji -> reactionEmoji.getEmoji().asUnicodeEmoji().get().equals(alarmClock)) //reaction must be alarm clock
                    .flatMap(reactionAddEvent -> reactionAddEvent.getMessage().map(message -> {
                        makeAppointment(message);
                        return Mono.empty();
                    }))
                    .doOnError(e -> e.printStackTrace());
        }

        @Override
        public Publisher<?> onVoiceStateUpdate(VoiceStateUpdateEvent event) {
            return Mono.just(event)
                    .filter(voiceStateUpdateEvent -> voiceStateUpdateEvent.isJoinEvent())
                    .flatMap(voiceStateUpdateEvent -> {
                        for(int i = appointments.size() - 1; i >= 0; i--) {
                            if(appointments.get(i).user.equals(voiceStateUpdateEvent.getCurrent().getUserId())) {
                                System.out.println("Removing user: " + appointments.get(i).user);
                                appointments.remove(i);
                            }
                        }
                        return Mono.empty();
                    });
        }
    };

    private static Mono<Object> celebrate(Appointment a) {
        System.out.println("Celebrating!");
        return client.getUserById(a.user).flatMap(user -> {
            String temp = "";
            for(int i = 0; i < 20; i++) temp += user.getMention();
            String finalTemp = temp;
            return client.getChannelById(a.channel).ofType(MessageChannel.class).flatMap(chan ->
                            chan.createMessage("LOSER ALERT " + user.getMention() + "!!!").then(
                                    chan.createMessage("YOUR'RE LATE YOU IDIOT!!!").then(
                                            chan.createMessage("WHAT ARE YOU WAITING FOR!?!?!").then(
                                                    chan.createMessage("https://tenor.com/view/police-siren-siren-gif-14993722").then(
                                                            chan.createMessage(spec -> spec.setContent(finalTemp).setTts(false)))))))
                    .then(Mono.fromRunnable(() -> appointments.remove(a)));
        });
    }

    private final static List<Appointment> appointments = new ArrayList<>();
    private static class Appointment {
        public final Instant time;
        public final Snowflake user;
        public final Snowflake channel;

        public Appointment(Instant time, Snowflake user, Snowflake channel) {
            this.time = time;
            this.user = user;
            this.channel = channel;
        }

        @Override
        public String toString() {
            return "Appointment{" +
                    "time=" + time +
                    ", user=" + user +
                    ", channel=" + channel +
                    '}';
        }
    }
    private static void makeAppointment(Message message) {
        if(containsTime(message.getContent())) {
            //find the time
            Pattern pattern = Pattern.compile("([0-1]?[0-9]|2[0-3]):[0-5][0-9]");
            Matcher matcher = pattern.matcher(message.getContent());
            String match = "";
            if(matcher.find()) {
                match = matcher.group();
            }
            if(match.length() == 4) match = 0 + match; //add leading zero



            LocalTime time = LocalTime.parse(match, DateTimeFormatter.ofPattern("HH:mm"));
            LocalDateTime date = LocalDate.now().atTime(time);
            Instant i = ZonedDateTime.of(date, ZoneId.of("US/Eastern")).toInstant();

            //infer am/pm
            if(Duration.between(Instant.now(), i).compareTo(Duration.ofHours(12)) > 0) i = i.minus(Duration.ofHours(12));
            if(Duration.between(Instant.now(), i).compareTo(Duration.ofHours(12)) > 0) i = i.minus(Duration.ofHours(12));
            if(Duration.between(Instant.now(), i).isNegative()) i = i.plus(Duration.ofHours(12));
            if(Duration.between(Instant.now(), i).isNegative()) i = i.plus(Duration.ofHours(12));

            System.out.println("Diff: " + Duration.between(Instant.now(), i));

            Snowflake u = message.getAuthor().get().getId();
            Snowflake c = message.getChannelId();
            appointments.add(new Appointment(i, u, c));

            System.out.println("Time rn " + Instant.now() + " time read " + i);
        }
    }

    private static boolean containsTime(String content) {
        return content.matches("^.*([0-1]?[0-9]|2[0-3]):[0-5][0-9].*$");
    }

    //keys.txt is stored in root dir and holds instance-specific data (eg. bot token)
    private static List<String> keys = null;
    public static List<String> getKeys() {
        if (keys == null) {
            try {
                keys = Files.readAllLines(Paths.get("./keys.txt"));
            } catch (IOException e) {
                e.printStackTrace();
            }
            //filter out things we commented out in our keys
            for (int i = keys.size() - 1; i >= 0; i--) {
                if (keys.get(i).indexOf('#') == 0) keys.remove(i);
            }
        }
        return keys;
    }
}
