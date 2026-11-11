#!/usr/bin/env python3
"""Validate the editorial German-dub feed; does not establish whether a claim is true."""
import json,sys,re
from pathlib import Path
from datetime import datetime,date,timedelta,timezone
from urllib.parse import urlsplit

def validate(path):
    raw=path.read_bytes();assert len(raw)<=1_000_000,'Feed exceeds 1 MB'
    data=json.loads(raw);assert type(data['schema_version']) is int and data['schema_version']==1,'Unknown schema'
    timestamp=lambda value:datetime.fromisoformat(value.replace('Z','+00:00'))
    now=datetime.now(timezone.utc);updated=timestamp(data['updated_at']);expires=timestamp(data['valid_until'])
    assert updated<=now+timedelta(minutes=5) and now<expires and timedelta(0)<expires-updated<=timedelta(days=14),'Expired or invalid validity window'
    def identifier(value):return isinstance(value,str) and re.fullmatch(r'[a-z0-9][a-z0-9-]{0,99}',value)
    def https(value):
        u=urlsplit(value)
        return u.scheme=='https' and u.hostname and '.' in u.hostname and not re.fullmatch(r'[0-9.]+',u.hostname) and ':' not in u.hostname and not u.hostname.endswith('.local') and not u.username and not u.password and u.port in (None,443) and len(value)<=2000
    sources={};series={};ids=set();episodes=set()
    assert len(data['sources'])<=500 and len(data['series'])<=2000 and len(data['releases'])<=5000
    for source in data['sources']:
        key=source['id'];assert identifier(key) and key not in sources,'Duplicate/invalid source'
        assert source['kind']=='official_publisher' and https(source['url']),'Official HTTPS source required'
        assert 2<=len(source['publisher'].strip())<=100 and 20<=len(source['evidence'].strip())<=1200
        assert timestamp(source['checked_at'])<=updated+timedelta(minutes=5),'Future source check'
        sources[key]=source
    for show in data['series']:
        key=show['id'];assert identifier(key) and key not in series and 1<=len(show['title'].strip())<=250,'Invalid series'
        assert not show.get('poster_url') or https(show['poster_url']),'Invalid image URL'
        series[key]=show
    for entry in data['releases']:
        key=entry['id'];assert identifier(key) and key not in ids,'Duplicate release ID'
        assert entry['type']=='episode' and entry['status']=='confirmed' and entry['audio_language']=='de' and entry['audio_format']=='dubbed' and entry['region']=='DE' and entry['release_kind']=='german_premiere','Not a confirmed German dubbed episode premiere'
        assert entry['source_id'] in sources and entry['series_id'] in series,'Missing source or series'
        assert type(entry['season']) is int and 0<=entry['season']<=999 and type(entry['episode']) is int and 1<=entry['episode']<=9999
        assert re.fullmatch(r'\d{4}-\d{2}-\d{2}',entry['german_release_date'])
        assert 2000<=date.fromisoformat(entry['german_release_date']).year<=2100
        assert entry['distribution'] in ('streaming','tv') and 2<=len(entry['provider'].strip())<=100
        episode=(entry['series_id'],entry['season'],entry['episode']);assert episode not in episodes,'Conflicting episode records'
        ids.add(key);episodes.add(episode)
    return len(series),len(ids),expires

if __name__=='__main__':
    path=Path(sys.argv[1]) if len(sys.argv)>1 else Path(__file__).resolve().parents[1]/'calendar/de-synchro.json'
    try:
        count,total,expires=validate(path)
        print(f'VALID: {count} series, {total} explicit episode dates, valid until {expires.isoformat()}')
        print('Editorial source review remains required; this validator checks data consistency only.')
    except (AssertionError,ValueError,KeyError,TypeError,OSError) as failure:
        print(f'INVALID: {failure}',file=sys.stderr);sys.exit(1)
