import requests
import time
import subprocess

# fetch current luts
resp = requests.get('http://127.0.0.1:8787/api/luts').json()
luts = resp['luts']
print("Total LUTs:", len(luts))

if len(luts) > 0:
    first_lut = luts[0]['id']
    print("Starring:", first_lut)
    requests.post('http://127.0.0.1:8787/api/favorites/bulk', json={"lut_ids": [first_lut], "favorite": True})
    
    resp = requests.get('http://127.0.0.1:8787/api/luts').json()
    print("Is favorite now?", any(l['id'] == first_lut and l['favorite'] for l in resp['luts']))

