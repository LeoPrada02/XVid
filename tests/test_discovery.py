"""This PC announcing itself on the home network (mDNS / DNS-SD), so the phone app finds it at any address.

The phone asks the network for the _xvid._tcp service; the PC answers with where it is (SRV and A),
and who it is (TXT: its id and name). This isn't HTTP, so unlike the API tests these feed the responder
DNS packets as the phone sends them, and read its replies.
"""

import socket
import struct

from app import discovery

PC_ID = "0f1e2d3c4b5a69788796a5b4c3d2e1f0"
RECORDS = discovery.records(PC_ID, "Laptop", 8443, "192.0.2.31")

PTR, A, TXT, SRV, ANY = 12, 1, 16, 33, 255


def query(*questions: tuple[str, int], query_id: int = 0, unicast: bool = False) -> bytes:
    packet = struct.pack(">HHHHHH", query_id, 0, len(questions), 0, 0, 0)
    for name, qtype in questions:
        packet += name_bytes(name) + struct.pack(">HH", qtype, 0x8001 if unicast else 1)
    return packet


def name_bytes(name: str) -> bytes:
    return b"".join(bytes([len(label)]) + label.encode() for label in name.split(".")) + b"\0"


def read_name(data: bytes, offset: int) -> tuple[str, int]:
    labels, end = [], None
    while data[offset]:
        if data[offset] >= 0xC0:  # a pointer to a name earlier in the packet
            end = end or offset + 2
            offset = struct.unpack_from(">H", data, offset)[0] & 0x3FFF
            continue
        labels.append(data[offset + 1:offset + 1 + data[offset]].decode())
        offset += 1 + data[offset]
    return ".".join(labels), end or offset + 1


def parse(packet: bytes) -> dict:
    """A DNS response: its header, and every record as (name, type, ttl, value)."""
    query_id, flags, qd, an, ns, ar = struct.unpack_from(">HHHHHH", packet)
    offset = 12
    for _ in range(qd):
        offset = read_name(packet, offset)[1] + 4
    records = []
    for _ in range(an + ns + ar):
        name, offset = read_name(packet, offset)
        rtype, _rclass, ttl, length = struct.unpack_from(">HHIH", packet, offset)
        offset += 10
        rdata = packet[offset:offset + length]
        if rtype == PTR:
            value = read_name(packet, offset)[0]
        elif rtype == SRV:
            value = (struct.unpack_from(">H", rdata, 4)[0], read_name(packet, offset + 6)[0])
        elif rtype == TXT:
            value, i = {}, 0
            while i < len(rdata):
                key, _, val = rdata[i + 1:i + 1 + rdata[i]].decode().partition("=")
                value[key], i = val, i + 1 + rdata[i]
        elif rtype == A:
            value = socket.inet_ntoa(rdata)
        else:
            value = rdata
        records.append((name, rtype, ttl, value))
        offset += length
    return {"id": query_id, "flags": flags, "questions": qd, "answers": an, "records": records}


def test_a_search_for_xvid_pcs_finds_this_pc_with_its_id_name_and_address():
    reply = discovery.answer(query(("_xvid._tcp.local", PTR)), RECORDS)
    response = parse(reply)

    assert response["flags"] & 0x8000  # a response
    instance = f"{PC_ID}._xvid._tcp.local"
    host = f"xvid-{PC_ID}.local"
    by_type = {rtype: (name, value) for name, rtype, _ttl, value in response["records"]}
    assert by_type[PTR] == ("_xvid._tcp.local", instance)
    assert by_type[SRV] == (instance, (8443, host))
    assert by_type[TXT] == (instance, {"id": PC_ID, "name": "Laptop"})
    assert by_type[A] == (host, "192.0.2.31")
    assert response["answers"] == 1  # the rest come along as additional records


def test_resolving_the_found_service_answers_its_address_and_id():
    instance = f"{PC_ID}._xvid._tcp.local"
    response = parse(discovery.answer(query((instance, SRV), (instance, TXT)), RECORDS))
    types = [rtype for _name, rtype, _ttl, _value in response["records"]]
    assert types[:2] == [SRV, TXT] and A in types


def test_names_are_matched_whatever_their_case():
    assert discovery.answer(query(("_XVID._tcp.Local", PTR)), RECORDS) is not None
    assert discovery.answer(query((f"xvid-{PC_ID.upper()}.local", ANY)), RECORDS) is not None


def test_other_questions_are_left_to_other_devices():
    assert discovery.answer(query(("_googlecast._tcp.local", PTR)), RECORDS) is None
    assert discovery.answer(query(("_xvid._tcp.local", SRV)), RECORDS) is None


def test_answers_from_other_devices_are_not_answered():
    response_from_elsewhere = bytearray(query(("_xvid._tcp.local", PTR)))
    response_from_elsewhere[2] = 0x84
    assert discovery.answer(bytes(response_from_elsewhere), RECORDS) is None


def test_broken_packets_are_ignored():
    for packet in (b"", b"\0" * 5, query(("_xvid._tcp.local", PTR))[:20], b"\0" * 4 + b"\0\1" + b"\0" * 6 + b"\xc0\x0c"):
        assert discovery.answer(packet, RECORDS) is None


def test_a_one_off_query_from_another_port_gets_a_direct_answer_with_its_id_and_question():
    reply = discovery.answer(query(("_xvid._tcp.local", PTR), query_id=4242), RECORDS, legacy=True)
    response = parse(reply)
    assert response["id"] == 4242 and response["questions"] == 1
    assert all(ttl <= 10 for _name, _rtype, ttl, _value in response["records"])


def test_the_announcement_carries_every_record():
    response = parse(discovery.announcement(RECORDS))
    assert response["flags"] & 0x8000 and response["questions"] == 0
    assert sorted(rtype for _name, rtype, _ttl, _value in response["records"]) == [A, PTR, TXT, SRV]
