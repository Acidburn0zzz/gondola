/*
 * Copyright 2015, Yahoo Inc.
 * Copyrights licensed under the New BSD License.
 * See the accompanying LICENSE file for terms.
 */

package com.yahoo.gondola;

import com.yahoo.gondola.core.CoreCmd;
import com.yahoo.gondola.core.CoreMember;
import com.yahoo.gondola.core.Message;
import com.yahoo.gondola.core.MessagePool;
import com.yahoo.gondola.core.Peer;
import com.yahoo.gondola.core.Stats;

import com.yahoo.gondola.impl.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

import javax.management.InstanceNotFoundException;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.ObjectName;

/**
 * Usage to commit a command:
 * <pre>
 *   Config config = Config.fromFile(confFile));
 *   Gondola gondola = new Gondola(config, hostId);
 *   Shard shard = gondola.getShard(shardId);
 *   Command command = shard.checkoutCommand();
 *   byte[] buffer = new byte[]{0, 1, 2, 3}; // some data to commit
 *   // Blocks until command has been committed
 *   command.commit(buffer, 0, buffer.length);
 * </pre>
 * Usage to get a committed command:
 * <pre>
 *   // Blocks until command at index 500 has been committed
 *   Command command = shard.getCommittedCommand(500);
 * </pre>
 */
public class Gondola implements Stoppable {
    final static Logger logger = LoggerFactory.getLogger(Gondola.class);

    Config config;
    String hostId;
    Stats stats = new Stats();
    MessagePool messagePool;
    Clock clock;
    Network network;
    Storage storage;

    // Get the pid of this process
    final String processId = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];

    List<Shard> shards = new ArrayList<Shard>();
    Map<String, Shard> shardMap = new HashMap<>();
    Map<Integer, CoreMember> memberMap = new HashMap<>();

    List<Consumer<RoleChangeEvent>> listeners = new CopyOnWriteArrayList<>();

    // Change events are done by a separate thread, to avoid delaying the gondola timeouts.
    // This queue is used to deliver the change events to the thread.
    BlockingQueue<RoleChangeEvent> roleChangeEventQueue = new LinkedBlockingQueue<>();

    // List of threads running in this class
    List<Thread> threads = new ArrayList<>();

    // JMX variables
    final MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();

    ObjectName objectName;

    /**
     * @param hostId the non-null id of the current host on which to start Gondola.
     */
    public Gondola(Config config, String hostId) throws Exception {
        this.config = config;
        this.hostId = hostId;
        logger
            .info("------- Gondola init: {}, {}, pid={} -------", hostId, config.getAddressForHost(hostId), processId);
    }

    /**
     * Starts all threads and enables the Gondola instance.
     */
    public void start() throws Exception {
        logger.info("Starting up Gondola host {}...", hostId);

        // Initialize static config values
        CoreCmd.initConfig(config);
        Message.initConfig(config);
        Peer.initConfig(config);

        messagePool = new MessagePool(config, stats);

        // Create implementations
        String clockClassName = config.get(config.get("clock.impl") + ".class");
        clock = (Clock) Class.forName(clockClassName).getConstructor(Gondola.class, String.class)
                .newInstance(this, hostId);

        String networkClassName = config.get(config.get("network.impl") + ".class");
        network = (Network) Class.forName(networkClassName).getConstructor(Gondola.class, String.class)
                .newInstance(this, hostId);

        String storageClassName = config.get(config.get("storage.impl") + ".class");
        storage = (Storage) Class.forName(storageClassName).getConstructor(Gondola.class, String.class)
                .newInstance(this, hostId);

        // Create the shards running on a host
        List<String> shardIds = config.getShardIds(hostId);
        for (String shardId : shardIds) {
            Shard shard = new Shard(this, shardId);

            shards.add(shard);
            shardMap.put(shardId, shard);
        }

        // Start all objects
        clock.start();
        network.start();
        storage.start();
        for (Shard c : shards) {
            c.start();
        }

        // Start local threads
        assert threads.size() == 0;
        threads.add(new RoleChangeNotifier());
        threads.forEach(t -> t.start());
        objectName = new ObjectName("com.yahoo.gondola." + hostId + ":type=Stats");
        mbs.registerMBean(stats, objectName);
    }

    /**
     * Stops all threads and releases all resources. After this call, start() can be called again.
     *
     * @return true if no errors were detected.
     */
    public boolean stop() {
        logger.info("Stopping Gondola instance for host {}...", hostId);
        boolean status = network.stop();

        // Shut down all local threads
        status = Utils.stopThreads(threads) && status;

        // Shut down threads in all dependencies
        for (Shard shard : shards) {
            status = shard.stop() && status;
        }
        status = storage.stop() && status;
        status = clock.stop() && status;

        threads.clear();
        shards.clear();
        shardMap.clear();
        try {
            mbs.unregisterMBean(objectName);
        } catch (InstanceNotFoundException | MBeanRegistrationException e) {
            logger.info("Unregister MBean failed -- {}", e.getMessage());
        }
        return status;
    }

    /********************
     * methods
     *********************/

    public String getHostId() {
        return hostId;
    }

    public String getProcessId() {
        return processId;
    }

    /**
     * Returns the shards that reside on the host as specified in the constructor.
     *
     * @return non-null list of shards.
     * @throw IllegalArgumentException if host is not found
     */
    public List<Shard> getShardsOnHost() {
        return shards;
    }

    public Shard getShard(String id) {
        return shardMap.get(id);
    }

    Shard get(String id) {
        return shardMap.get(id);
    }

    public Storage getStorage() {
        return storage;
    }

    public Config getConfig() {
        return config;
    }

    public MessagePool getMessagePool() {
        return messagePool;
    }

    public Clock getClock() {
        return clock;
    }

    public Network getNetwork() {
        return network;
    }

    public Stats getStats() {
        return stats;
    }

    public void registerForRoleChanges(Consumer<RoleChangeEvent> listener) {
        listeners.add(listener);
    }

    public void unregisterForRoleChanges(Consumer<RoleChangeEvent> listener) {
        listeners.remove(listener);
    }

    public void notifyRoleChange(RoleChangeEvent evt) {
        roleChangeEventQueue.add(evt);
    }

    class RoleChangeNotifier extends Thread {
        public void run() {
            while (true) {
                try {
                    RoleChangeEvent evt = roleChangeEventQueue.take();
                    listeners.forEach(c -> c.accept(evt));
                } catch (InterruptedException e) {
                    return;
                } catch (Exception e) {
                    logger.error(e.getMessage(), e);
                }
            }
        }
    }
}
