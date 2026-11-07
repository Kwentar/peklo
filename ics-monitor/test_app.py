import unittest
from datetime import date
from app import parse_events

def calendar(events):
    return ('BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Test//EN\r\n'+events+'END:VCALENDAR\r\n').encode()
def event(body): return 'BEGIN:VEVENT\r\n'+body+'END:VEVENT\r\n'
class CalendarTests(unittest.TestCase):
    def test_recurring_exception_and_cancel(self):
        data=calendar(event('UID:r\r\nDTSTART:20261106T090000Z\r\nDTEND:20261106T100000Z\r\nRRULE:FREQ=DAILY;COUNT=4\r\nSUMMARY:Daily\r\n')+event('UID:r\r\nRECURRENCE-ID:20261107T090000Z\r\nDTSTART:20261107T110000Z\r\nDTEND:20261107T120000Z\r\nSUMMARY:Moved\r\n')+event('UID:c\r\nDTSTART:20261107T090000Z\r\nSTATUS:CANCELLED\r\nSUMMARY:Cancelled\r\n'))
        result=parse_events(data,date(2026,11,7))
        self.assertEqual(len(result),1);self.assertEqual(result[0]['title'],'Moved');self.assertIn('14:00:00+03:00',result[0]['start'])
    def test_all_day_and_midnight_overlap(self):
        data=calendar(event('UID:a\r\nDTSTART;VALUE=DATE:20261107\r\nDTEND;VALUE=DATE:20261108\r\nSUMMARY:All day\r\n')+event('UID:b\r\nDTSTART:20261106T203000Z\r\nDTEND:20261106T223000Z\r\nSUMMARY:Night\r\n'))
        result=parse_events(data,date(2026,11,7));self.assertEqual(len(result),2);self.assertTrue(any(e['allDay'] for e in result))
    def test_exdate_and_other_day(self):
        data=calendar(event('UID:r\r\nDTSTART:20261106T090000Z\r\nRRULE:FREQ=DAILY;COUNT=4\r\nEXDATE:20261107T090000Z\r\nSUMMARY:Daily\r\n'))
        self.assertEqual(parse_events(data,date(2026,11,7)),[])
    def test_invalid(self):
        with self.assertRaises(Exception):parse_events(b'<html>login</html>')
if __name__=='__main__':unittest.main()
