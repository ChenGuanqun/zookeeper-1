/*
 * Copyright 2008, Yahoo! Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.yahoo.zookeeper.server.quorum;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.HashMap;
import java.util.Random;
import java.util.Map.Entry;

import com.yahoo.zookeeper.server.ZooLog;
import com.yahoo.zookeeper.server.quorum.QuorumPeer.QuorumServer;
import com.yahoo.zookeeper.server.quorum.QuorumPeer.ServerState;

public class LeaderElection {
    static public class Vote {
        public Vote(long id, long zxid) {
            this.id = id;
            this.zxid = zxid;
        }

        public long id;

        public long zxid;

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Vote)) {
                return false;
            }
            Vote other = (Vote) o;
            return id == other.id && zxid == other.zxid;
        }

        @Override
        public int hashCode() {
            return (int) (id & zxid);
        }

    }

    QuorumPeer self;

    public LeaderElection(QuorumPeer self) {
        this.self = self;
    }

    public static class ElectionResult {
        public Vote vote;

        public int count;

        public Vote winner;

        public int winningCount;
    }

    private ElectionResult countVotes(HashMap<InetSocketAddress, Vote> votes) {
        ElectionResult result = new ElectionResult();
        // Initialize with null vote
        result.vote = new Vote(Long.MIN_VALUE, Long.MIN_VALUE);
        result.winner = new Vote(Long.MIN_VALUE, Long.MIN_VALUE);
        Collection<Vote> votesCast = votes.values();
        // First make the views consistent. Sometimes peers will have
        // different zxids for a server depending on timing.
        for (Vote v : votesCast) {
            for (Vote w : votesCast) {
                if (v.id == w.id) {
                    if (v.zxid < w.zxid) {
                        v.zxid = w.zxid;
                    }
                }
            }
        }
        HashMap<Vote, Integer> countTable = new HashMap<Vote, Integer>();
        // Now do the tally
        for (Vote v : votesCast) {
            Integer count = countTable.get(v);
            if (count == null) {
                count = new Integer(0);
            }
            countTable.put(v, count + 1);
            if (v.id == result.vote.id) {
                result.count++;
            } else if (v.zxid > result.vote.zxid
                    || (v.zxid == result.vote.zxid && v.id > result.vote.id)) {
                result.vote = v;
                result.count = 1;
            }
        }
        result.winningCount = 0;
        ZooLog.logWarn("Election tally: ");
        for (Entry<Vote, Integer> entry : countTable.entrySet()) {
            if (entry.getValue() > result.winningCount) {
                result.winningCount = entry.getValue();
                result.winner = entry.getKey();
            }
            ZooLog.logWarn(entry.getKey().id + "\t-> " + entry.getValue());
        }
        return result;
    }

    /*
     * 快速选举算法,将自己的sid,zxid分别发送给其他server。同时接收其他server发送过来的sid,zxid信息。
     * 选取其中zxid最大的作为leader,如果相同选择sid大的。保证在相同条件下，所有server做出一致判断（具有相同的input）。
     * 
     * 这个方法并没有check在选举后，所有的server选出的leader是否一样，实际也不需要。只要半数通过就好，系统正常运行。
     * 其他没有能够更新的server,会在连入主网（多数server互联的网）后发现自己commit的被拒绝，尝试重新选举此时更新到正确的leader。
     * 或者其他server也有可能一直处在LEADING状态下循环。反正只要something is going wrong,都会回到LEADING状态。
     */
    public Vote lookForLeader() throws InterruptedException {
        self.currentVote = new Vote(self.myid, self.getLastLoggedZxid());
        // We are going to look for a leader by casting a vote for ourself
        byte requestBytes[] = new byte[4];
        ByteBuffer requestBuffer = ByteBuffer.wrap(requestBytes);
        byte responseBytes[] = new byte[28];
        ByteBuffer responseBuffer = ByteBuffer.wrap(responseBytes);
        /* The current vote for the leader. Initially me! */
        DatagramSocket s = null;
        try {
            s = new DatagramSocket();
            s.setSoTimeout(200);
        } catch (SocketException e1) {
            e1.printStackTrace();
            System.exit(4);
        }
        DatagramPacket requestPacket = new DatagramPacket(requestBytes,
                requestBytes.length);
        DatagramPacket responsePacket = new DatagramPacket(responseBytes,
                responseBytes.length);
        HashMap<InetSocketAddress, Vote> votes = new HashMap<InetSocketAddress, Vote>(
                self.quorumPeers.size());
        int xid = new Random().nextInt();
        while (self.running) {
            votes.clear();
            requestBuffer.clear();
            requestBuffer.putInt(xid);
            requestPacket.setLength(4);
            for (QuorumServer server : self.quorumPeers) {
                requestPacket.setSocketAddress(server.addr);
                try {
                    s.send(requestPacket);
                    responsePacket.setLength(responseBytes.length);
                    s.receive(responsePacket);
                    if (responsePacket.getLength() != responseBytes.length) {
                        ZooLog.logError("Got a short response: "
                                + responsePacket.getLength());
                        continue;
                    }
                    responseBuffer.clear();
                    int recvedXid = responseBuffer.getInt();
                    if (recvedXid != xid) {
                        ZooLog.logError("Got bad xid: expected " + xid
                                + " got " + recvedXid);
                        continue;
                    }
                    long peerId = responseBuffer.getLong();
                    server.id = peerId;
                    Vote vote = new Vote(responseBuffer.getLong(),
                            responseBuffer.getLong());
                    InetSocketAddress addr = (InetSocketAddress) responsePacket
                            .getSocketAddress();
                    votes.put(addr, vote);
                } catch (IOException e) {
                    // Errors are okay, since hosts may be
                    // down
                    // ZooKeeperServer.logException(e);
                }
            }
            ElectionResult result = countVotes(votes);
            if (result.winner.id >= 0) {
                self.currentVote = result.vote;
                if (result.winningCount > (self.quorumPeers.size() / 2)) {
                    self.currentVote = result.winner;
                    s.close();
                    self.state = (self.currentVote.id == self.myid) ? ServerState.LEADING
                            : ServerState.FOLLOWING;
                    if (self.state == ServerState.FOLLOWING) {
                        Thread.sleep(100);
                    }
                    return self.currentVote;
                }
            }
            Thread.sleep(1000);
        }
        return null;
    }
}
