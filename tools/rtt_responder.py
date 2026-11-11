#!/usr/bin/env python3
"""RTT responder for the FastDDS Android app.

Subscribes to /android_chatter; republishes every message that starts with
"RTT " and does NOT already carry the " ~E" marker, appending the marker.
Loop-safe: the echoed copy is never re-echoed.

Run in WSL:
    source /opt/ros/<distro>/setup.bash
    python3 /tmp/rtt_responder.py
"""
import rclpy
from rclpy.node import Node
from std_msgs.msg import String

PREF = "RTT "
MARK = " ~E"


class RttResponder(Node):
    def __init__(self):
        super().__init__("android_rtt_responder")
        self.sub = self.create_subscription(String, "/android_chatter", self.cb, 10)
        self.pub = self.create_publisher(String, "/android_chatter", 10)
        self.count = 0

    def cb(self, msg):
        d = msg.data
        if d.startswith(PREF) and not d.endswith(MARK):
            self.pub.publish(String(data=d + MARK))
            self.count += 1
            if self.count % 50 == 0:
                self.get_logger().info("echoed %d pings" % self.count)


def main():
    rclpy.init()
    rclpy.spin(RttResponder())
    rclpy.shutdown()


if __name__ == "__main__":
    main()
