package org.zeromq.dafka;

import static org.zeromq.ZActor.SimpleActor;

import java.util.*;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.UnrecognizedOptionException;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.config.Configurator;
import org.zeromq.*;
import org.zeromq.ZMQ.Socket;
import org.zproto.DafkaProto;

/**
 * <p>Skeleton actor for the DafkaConsumer</p>
 *
 * <p>The skeleton already has the DafkaBeacon implemented so discovering and connecting to peers is given.</p>
 */
public class DafkaConsumer extends SimpleActor {

    private static final Logger log = LogManager.getLogger(DafkaConsumer.class);

    private DafkaBeacon beacon;
    private ZActor beaconActor;
    private Socket sub;
    private Socket pub;
    private String consumerAddress;
    private Map<String, Long> partitions = new HashMap<>();

    public DafkaConsumer() {
        this.beacon = new DafkaBeacon();
    }

    @Override
    public List<Socket> createSockets(ZContext ctx, Object... args) {
        Properties properties = (Properties) args[0];

        this.beaconActor = new ZActor(ctx, this.beacon, null, args);
        this.beaconActor.recv(); // Wait for signal that beacon is connected to tower

        sub = ctx.createSocket(SocketType.SUB);
        pub = ctx.createSocket(SocketType.PUB);

        // HINT: Don't forget to return your sockets here otherwise they won't get passed as parameter into the start() method
        return Arrays.asList(beaconActor.pipe(), sub, pub);
    }

    @Override
    public void start(Socket pipe, List<Socket> sockets, ZPoller poller) {
        consumerAddress = UUID.randomUUID().toString();

        int publisherSocketPort = pub.bindToRandomPort("tcp://*");

        beacon.start(beaconActor, consumerAddress, publisherSocketPort);
        boolean rc = poller.register(beaconActor.pipe(), ZPoller.IN);

        // HINT: This is the place where you want to subscribe to topics!

        // Subscribe to direct messages
        DafkaProto.subscribe(sub, DafkaProto.DIRECT_MSG, consumerAddress);
        DafkaProto.subscribe(sub, DafkaProto.DIRECT_HEAD, consumerAddress);

        // HINT: Don't forget to register your inbound sockets with the poller!
        poller.register(sub, ZPoller.IN);

        // Signals the actor create about the successful startup by sending a zero byte.
        pipe.send(new byte[]{0});
        log.info("Consumer started...");
    }

    @Override
    public boolean finished(Socket pipe) {
        beacon.terminate(beaconActor);
        log.info("Consumer stopped!");
        return super.finished(pipe);
    }

    @Override
    public boolean stage(Socket socket, Socket pipe, ZPoller poller, int events) {
        // HINT: This is the place where you get notified about new messages on sockets registered with the poller.
        // HINT: It is useful to log the incoming message ;)

        if (socket.equals(beaconActor.pipe())) {
            handleBeaconActor(socket);
        }

        if (socket.equals(sub)) {
            handleSubscriber(pipe);
        }

        return true;
    }

    private void handleSubscriber(Socket pipe) {
        DafkaProto message = DafkaProto.recv(sub);
        String partition = message.address();

        long lastSequence = -1;
        if (partitions.containsKey(partition)) {
            lastSequence = partitions.get(partition);
        }

        log.info("{} : {}-Message from {} with sequence {}", partition, message.id(), message.topic(), message.sequence());

        if (message.id() == DafkaProto.MSG) {
            if (lastSequence + 1 < message.sequence()) {
                fetch(message, lastSequence);
            } else {
                acceptCurrentMessage(message, pipe);
            }
        }

        if (message.id() == DafkaProto.DIRECT_MSG) {
            if (lastSequence + 1 == message.sequence()) {
                acceptCurrentMessage(message, pipe);
            }
        }

        if (message.id() == DafkaProto.HEAD || message.id() == DafkaProto.DIRECT_HEAD) {
            if (lastSequence < message.sequence()) {
                fetch(message, lastSequence);
            }
        }
    }

    private void handleBeaconActor(Socket socket) {
        String command = socket.recvStr();
        String address = socket.recvStr();

        if ("CONNECT".equals(command)) {
            log.info("Connecting to {}", address);
            sub.connect(address);
        } else if ("DISCONNECT".equals(command)) {
            log.info("Disconnecting from {}", address);
            sub.disconnect(address);
        } else {
            log.error("Transport: Unknown command {}", command);
            System.exit(1);
        }
    }

    private void acceptCurrentMessage(DafkaProto message, Socket pipe) {
        partitions.put(message.address(), message.sequence());
        pipe.send(message.address(), ZMQ.SNDMORE);
        pipe.send(message.content().toString(), 0);
    }

    private void fetch(DafkaProto recv, long lastSequence) {
        long start = lastSequence + 1;
        long count = recv.sequence() - lastSequence;
        DafkaProto dafkaProto = new DafkaProto(DafkaProto.FETCH);
        dafkaProto.setAddress(consumerAddress);
        dafkaProto.setTopic(recv.address());
        dafkaProto.setSubject(recv.subject());
        dafkaProto.setSequence(start);
        dafkaProto.setCount(count);
        dafkaProto.send(pub);
        log.info("Fetching {} starting from {} for partition {}...", count, start, recv.address());
    }

    private void getHeads(String topic) {
        DafkaProto getHeadsProto = new DafkaProto(DafkaProto.GET_HEADS);
        getHeadsProto.setAddress(consumerAddress);
        getHeadsProto.setTopic(topic);
        getHeadsProto.send(pub);
    }

    @Override
    public boolean backstage(Socket pipe, ZPoller poller, int events) {
        // HINT: This is the place where you get notified about new messages from the creator of the actor.

        String command = pipe.recvStr();
        switch (command) {
            case "$TERM":
                return false;
            default:
                log.error("Invalid command {}", command);
        }
        return true;
    }

    /**
     * This methods subscribes a consumer to all partitions of a Dafka topic.
     *
     * @param topic Name of the topic
     */
    public void subscribe(String topic) {
        log.debug("Subscribe to topic {}", topic);
        DafkaProto.subscribe(sub, DafkaProto.MSG, topic);
        DafkaProto.subscribe(sub, DafkaProto.HEAD, topic);

        getHeads(topic);
    }

    public void terminate(ZActor actor) {
        actor.send("$TERM");
    }

    public static void main(String[] args) throws InterruptedException, ParseException {
        Properties consumerProperties = new Properties();
        Options options = new Options();
        options.addOption("from_beginning", "Consume messages from beginning of partition");
        options.addOption("pub", true, "Tower publisher address");
        options.addOption("sub", true, "Tower subscriber address");
        options.addOption("verbose", "Enable verbose logging");
        options.addOption("help", "Displays this help");
        CommandLineParser parser = new DefaultParser();
        try {
            final CommandLine cmd = parser.parse(options, args);

            if (cmd.hasOption("help")) {
                HelpFormatter formatter = new HelpFormatter();
                formatter.printHelp("dafka_console_consumer", options);
                return;
            }

            if (cmd.hasOption("verbose")) {
                Configurator.setRootLevel(Level.DEBUG);
            } else {
                Configurator.setRootLevel(Level.ERROR);
            }

            if (cmd.hasOption("from_beginning")) {
                consumerProperties.setProperty("consumer.offset.reset", "earliest");
            }
            if (cmd.hasOption("pub")) {
                consumerProperties.setProperty("beacon.pub_address", cmd.getOptionValue("pub"));
            }
            if (cmd.hasOption("sub")) {
                consumerProperties.setProperty("beacon.sub_address", cmd.getOptionValue("sub"));
            }
        } catch (UnrecognizedOptionException exception) {
            System.out.println(exception.getMessage());
            HelpFormatter formatter = new HelpFormatter();
            formatter.printHelp("dafka_console_consumer", options);
            return;
        }

        ZContext context = new ZContext();

        final DafkaConsumer dafkaConsumer = new DafkaConsumer();
        ZActor actor = new ZActor(context, dafkaConsumer, null, Arrays.asList(consumerProperties).toArray());

        // Wait until actor is ready
        Socket pipe = actor.pipe();
        byte[] signal = pipe.recv();
        assert signal[0] == 0;

        // Give time until connected to pubs and stores
        Thread.sleep(1000);
        dafkaConsumer.subscribe("HELLO");

        final Thread zmqThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                // TODO: Retrieve messages from subscribed topics and print them!
                log.info("{}: {}", pipe.recvStr(), pipe.recvStr());
                // HINT: Call receive on the actor pipe
            }
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            dafkaConsumer.terminate(actor);
            try {
                zmqThread.interrupt();
                zmqThread.join();
                context.close();
            } catch (InterruptedException e) {
            }
        }));

        zmqThread.start();
    }
}
