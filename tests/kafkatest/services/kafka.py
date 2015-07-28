# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

from ducktape.services.service import Service
from ducktape.utils.util import wait_until

from kafkatest.process_signal import *

from concurrent import futures
import json
import re
import time


class KafkaService(Service):

    logs = {
        "kafka_log": {
            "path": "/mnt/kafka.log",
            "collect_default": True},
        "kafka_operational_logs": {
            "path": "/mnt/kafka-operational-logs",
            "collect_default": True},
        "kafka_data": {
            "path": "/mnt/kafka-data-logs",
            "collect_default": False}
    }

    def __init__(self, context, num_nodes, zk, topics=None):
        """
        :type context
        :type zk: ZookeeperService
        :type topics: dict
        """
        super(KafkaService, self).__init__(context, num_nodes)
        self.zk = zk
        self.topics = topics

    def start(self):
        super(KafkaService, self).start()

        if not wait_until(lambda: self.all_alive(), timeout_sec=20, backoff_sec=.5):
            raise RuntimeError("Timed out waiting for Kafka cluster to start.")

        # Create topics if necessary
        if self.topics is not None:

            for topic, topic_cfg in self.topics.items():
                if topic_cfg is None:
                    topic_cfg = {}

                topic_cfg["topic"] = topic
                self.create_topic(topic_cfg)

    def all_alive(self):
        for node in self.nodes:
            if not self.alive(node):
                return False
        return True

    def alive(self, node):
        try:
            # Try opening tcp connection and immediately closing by sending EOF
            node.account.ssh("echo EOF | nc %s %d" % (node.account.hostname, 9092), allow_fail=False)
            return True
        except:
            return False

    def start_node(self, node):
        props_file = self.render('kafka.properties', node=node, broker_id=self.idx(node))
        self.logger.debug("kafka.properties:")
        self.logger.debug(props_file)
        node.account.create_file("/mnt/kafka.properties", props_file)

        cmd = "export LOG_DIR=/mnt/kafka-operational-logs/; "
        cmd += "/opt/kafka/bin/kafka-server-start.sh /mnt/kafka.properties 1>> /mnt/kafka.log 2>> /mnt/kafka.log & echo $! > /mnt/kafka.pid"
        self.logger.debug("Attempting to start KafkaService on %s" % str(node.account))

        with futures.ThreadPoolExecutor(max_workers=1) as executor:
            future = executor.submit(node.account.ssh(cmd))

    def pids(self, node):
        """Return process ids associated with running processes on the given node."""
        try:
            return [pid for pid in node.account.ssh_capture("cat /mnt/kafka.pid", callback=int)]
        except:
            return []

    def signal_node(self, node, sig=SIGTERM):
        pids = self.pids(node)
        for pid in pids:
            node.account.signal(pid, sig)

    def signal_leader(self, topic, partition=0, sig=SIGTERM):
        leader = self.leader(topic, partition)
        self.signal_node(leader, sig)

    def stop_node(self, node, clean_shutdown=True):
        pids = self.pids(node)
        sig = SIGTERM if clean_shutdown else SIGKILL

        for pid in pids:
            node.account.signal(pid, sig, allow_fail=False)

        node.account.ssh("rm -f /mnt/kafka.pid", allow_fail=False)

    def clean_node(self, node):
        node.account.ssh(
            "rm -rf /mnt/kafka-operational-logs /mnt/kafka-logs /mnt/kafka.properties /mnt/kafka.log /mnt/kafka.pid",
            allow_fail=False)

    def create_topic(self, topic_cfg):
        node = self.nodes[0]  # any node is fine here
        self.logger.info("Creating topic %s with settings %s", topic_cfg["topic"], topic_cfg)

        cmd = "/opt/kafka/bin/kafka-topics.sh --zookeeper %(zk_connect)s --create "\
            "--topic %(topic)s --partitions %(partitions)d --replication-factor %(replication)d" % {
                'zk_connect': self.zk.connect_setting(),
                'topic': topic_cfg.get("topic"),
                'partitions': topic_cfg.get('partitions', 1),
                'replication': topic_cfg.get('replication-factor', 1)
            }

        if "configs" in topic_cfg.keys() and topic_cfg["configs"] is not None:
            for config_name, config_value in topic_cfg["configs"].items():
                cmd += " --config %s=%s" % (config_name, str(config_value))

        self.logger.info("Running topic creation command...\n")
        node.account.ssh(cmd)

        time.sleep(1)
        self.logger.info("Checking to see if topic was properly created...\n%s" % cmd)

        for line in self.describe_topic(topic_cfg["topic"]).split("\n"):
            self.logger.info(line)

    def describe_topic(self, topic):
        node = self.nodes[0]
        cmd = "/opt/kafka/bin/kafka-topics.sh --zookeeper %s --topic %s --describe" % \
              (self.zk.connect_setting(), topic)
        output = ""
        for line in node.account.ssh_capture(cmd):
            output += line
        return output

    def verify_reassign_partitions(self, reassignment):
        """Run the reassign partitions admin tool in "verify" mode."""
        node = self.nodes[0]
        json_file = "/tmp/" + str(time.time()) + "_reassign.json"

        # reassignment to json
        json_str = json.dumps(reassignment)
        json_str = json.dumps(json_str)

        # create command
        cmd = "echo %s > %s && " % (json_str, json_file)
        cmd += "/opt/kafka/bin/kafka-reassign-partitions.sh "\
                "--zookeeper %(zk_connect)s "\
                "--reassignment-json-file %(reassignment_file)s "\
                "--verify" % {'zk_connect': self.zk.connect_setting(),
                                'reassignment_file': json_file}
        cmd += " && sleep 1 && rm -f %s" % json_file

        # send command
        self.logger.info("Verifying parition reassignment...")
        self.logger.debug(cmd)
        output = ""
        for line in node.account.ssh_capture(cmd):
            output += line

        self.logger.debug(output)

        if re.match(".*is in progress.*", output) is not None:
            return False
        return True

    def execute_reassign_partitions(self, reassignment):
        """Run the reassign partitions admin tool in "verify" mode."""
        node = self.nodes[0]
        json_file = "/tmp/" + str(time.time()) + "_reassign.json"

        # reassignment to json
        json_str = json.dumps(reassignment)
        json_str = json.dumps(json_str)

        # create command
        cmd = "echo %s > %s && " % (json_str, json_file)
        cmd += "/opt/kafka/bin/kafka-reassign-partitions.sh "\
                "--zookeeper %(zk_connect)s "\
                "--reassignment-json-file %(reassignment_file)s "\
                "--execute" % {'zk_connect': self.zk.connect_setting(),
                                'reassignment_file': json_file}
        cmd += " && sleep 1 && rm -f %s" % json_file

        # send command
        self.logger.info("Executing partition reassignment...")
        self.logger.debug(cmd)
        output = ""
        for line in node.account.ssh_capture(cmd):
            output += line

        self.logger.debug("Verify partition reassignment:")
        self.logger.debug(output)

    def bounce_node(self, node, signal=SIGTERM, condition=None, condition_timeout_sec=5, condition_backoff_sec=.25):
        """Helper method for the very common test task of bouncing some node.

        :param node A node in the service
        :param signal Process control signal. Preferably of the form "SIGSTOP" rather than 17, since integers don't map across os.
        :param condition Wait for this condition to be true before restarting
        :param condition_timeout_sec Max time to wait for wake condition to become true
        :param condition_backoff_sec
        """
        assert signal in {SIGKILL, SIGSTOP, SIGTERM}
        self.signal_node(node, signal)

        if condition:
            # Wait until some condition is true before bringing the node back up
            if not wait_until(condition, timeout_sec=condition_timeout_sec, backoff_sec=condition_backoff_sec):
                raise RuntimeError("Timed out waiting for " + condition.__name__)

        if signal == SIGSTOP:
            self.signal_node(node, "SIGCONT")
        else:
            self.start_node(node)

    def leader(self, topic, partition=0):
        """ Get the leader replica for the given topic and partition.
        """
        topic_partition_data = self.zk.get_data("/brokers/topics/%s/partitions/%d/state" % (topic, partition))
        if topic_partition_data is None:
            raise Exception("Error finding data for topic %s and partition %d." % (topic, partition))

        leader_idx = int(topic_partition_data["leader"])
        return self.get_node(leader_idx)

    def controller(self):
        """
        Get the current controller
        """
        controller_data = self.zk.get_data("/controller")
        if controller_data is None:
            raise Exception("Error finding controller.")

        controller_idx = int(controller_data["brokerid"])
        controller_node = self.get_node(controller_idx)

        if controller_node is None:
            raise Exception("Found no node corresponding to the id: %d" % controller_idx)
        return controller_node

    def bootstrap_servers(self):
        return ','.join([node.account.hostname + ":9092" for node in self.nodes])
