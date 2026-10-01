"""Announces this PC on the home network with mDNS / DNS-SD, so the phone app finds it at whatever
address the router gave it: the service _xvid._tcp.local, with the PC's id and name in its TXT record.

A small responder on the standard library: it answers the phone's questions about the service and
announces it when XVid starts and whenever this PC's address changes.
"""

import socket
import struct
import threading
import time

from app.network import lan_ip

SERVICE = "_xvid._tcp.local"
GROUP, PORT = "224.0.0.251", 5353
TTL = 120  # seconds; mDNS's usual for these records
LEGACY_TTL = 10  # for one-off queries from other ports, as mDNS asks
CHECK_EVERY = 30  # seconds between checks of this PC's address

PTR, A, TXT, SRV, ANY = 12, 1, 16, 33, 255
IN, CACHE_FLUSH = 1, 0x8000  # CACHE_FLUSH marks records only this PC answers for


def records(pc_id: str, name: str, port: int, ip: str) -> list[tuple]:
    """This PC's service records, as (name, type, class, rdata)."""
    instance = f"{pc_id}.{SERVICE}"
    host = f"xvid-{pc_id}.local"
    txt = b"".join(bytes([len(item)]) + item for item in (f"id={pc_id}".encode(), f"name={name[:60]}".encode()))
    return [
        (SERVICE, PTR, IN, encode_name(instance)),
        (instance, SRV, IN | CACHE_FLUSH, struct.pack(">HHH", 0, 0, port) + encode_name(host)),
        (instance, TXT, IN | CACHE_FLUSH, txt),
        (host, A, IN | CACHE_FLUSH, socket.inet_aton(ip)),
    ]


def answer(query: bytes, recs: list[tuple], legacy: bool = False) -> bytes | None:
    """The reply to [query], or None when it asks about nothing of this PC's (or isn't a query).

    [legacy] is for a one-off query from a port other than mDNS's: it gets a direct reply that repeats
    its id and questions, as plain DNS would.
    """
    try:
        query_id, flags, qdcount = struct.unpack_from(">HHH", query)
        if flags & 0x8000:  # an answer from another device
            return None
        offset, questions, wanted = 12, [], []
        for _ in range(qdcount):
            start = offset
            qname, offset = read_name(query, offset)
            qtype, _qclass = struct.unpack_from(">HH", query, offset)
            offset += 4
            questions.append(query[start:offset])
            wanted += [r for r in recs if r[0].lower() == qname.lower() and qtype in (r[1], ANY) and r not in wanted]
    except (struct.error, IndexError, ValueError, UnicodeDecodeError):
        return None
    if not wanted:
        return None
    # What a phone asks next comes along: where the service is, its TXT, and the host's address.
    extra = [r for r in recs if r not in wanted and r[1] != PTR]
    if legacy:
        return packet(wanted, extra, query_id, tuple(questions), LEGACY_TTL)
    return packet(wanted, extra)


def announcement(recs: list[tuple]) -> bytes:
    """Every record, unasked: what devices on the network cache when this PC appears or moves."""
    return packet(recs, [])


def packet(answers: list[tuple], extra: list[tuple], query_id: int = 0, questions: tuple[bytes, ...] = (),
           ttl: int = TTL) -> bytes:
    out = struct.pack(">HHHHHH", query_id, 0x8400, len(questions), len(answers), 0, len(extra))
    out += b"".join(questions)
    for name, rtype, rclass, rdata in answers + extra:
        out += encode_name(name) + struct.pack(">HHIH", rtype, rclass, ttl, len(rdata)) + rdata
    return out


def encode_name(name: str) -> bytes:
    return b"".join(bytes([len(label)]) + label for label in (part.encode() for part in name.split("."))) + b"\0"


def read_name(data: bytes, offset: int) -> tuple[str, int]:
    """The name at [offset], following compression pointers, and the offset just after it."""
    labels, end, jumps = [], None, 0
    while data[offset]:
        if data[offset] >= 0xC0:
            jumps += 1
            if jumps > 16:
                raise ValueError("pointer loop")
            end = end or offset + 2
            offset = struct.unpack_from(">H", data, offset)[0] & 0x3FFF
            continue
        length = data[offset]
        if offset + 1 + length > len(data):
            raise ValueError("name runs past the packet")
        labels.append(data[offset + 1:offset + 1 + length].decode())
        offset += 1 + length
    return ".".join(labels), end or offset + 1


def serve(pc_id: str, name: str, port: int) -> None:
    """Answers for this PC on the home network until XVid stops. Runs in its own thread."""
    while True:
        try:
            _serve(pc_id, name, port)
        except OSError as e:
            print(f"Finding this PC on the network is off for now ({e}). Phones use its last known address.")
            time.sleep(60)


def _serve(pc_id: str, name: str, port: int) -> None:
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP) as sock:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind(("", PORT))
        sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 255)
        sock.settimeout(1)
        ip, recs, next_check = None, [], 0.0
        while True:
            if time.monotonic() >= next_check:
                next_check = time.monotonic() + CHECK_EVERY
                current = lan_ip()
                if current != ip and not current.startswith("127."):
                    if ip:
                        try:
                            sock.setsockopt(socket.IPPROTO_IP, socket.IP_DROP_MEMBERSHIP, membership(ip))
                        except OSError:
                            pass  # that network is gone
                    sock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, membership(current))
                    sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_IF, socket.inet_aton(current))
                    ip, recs = current, records(pc_id, name, port, current)
                    for _ in range(2):  # twice, as mDNS asks, in case one is lost
                        sock.sendto(announcement(recs), (GROUP, PORT))
                        time.sleep(1)
            try:
                data, sender = sock.recvfrom(9000)
            except socket.timeout:
                continue
            except ConnectionResetError:  # Windows: a direct reply earlier didn't reach its sender
                continue
            legacy = sender[1] != PORT
            reply = answer(data, recs, legacy=legacy) if recs else None
            if reply:
                sock.sendto(reply, sender if legacy else (GROUP, PORT))


def membership(ip: str) -> bytes:
    return socket.inet_aton(GROUP) + socket.inet_aton(ip)


def start(pc_id: str, name: str, port: int) -> None:
    threading.Thread(target=serve, args=(pc_id, name, port), daemon=True, name="xvid-discovery").start()
