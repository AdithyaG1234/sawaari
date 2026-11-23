"""The example trip for the how-to video: Adithya's Goa itinerary, Option 1 (26 to 30 Nov 2026).

One Sawaari ride plan holds a start, up to 8 stops and a destination, which fits Option 1 exactly.
Coordinates are approximate town / landmark positions.
"""

YEAR = 2026

# (name on the map, lat, lon, schedule date "DD", time "HH:MM" 24h, remark)
POINTS = [
    ("Tumkur Road Toll Gate", 13.0880, 77.4300, 26, "04:00", "Start"),
    ("Benne Dose Hotel", 14.1500, 76.4700, 26, "07:00", "Breakfast halt"),
    ("Haveri", 14.7950, 75.3990, 26, "10:30", "Tea and rest break"),
    ("Dandeli", 15.2490, 74.6170, 26, "14:00", "Lunch, night stay"),
    ("Castle Rock", 15.3990, 74.3290, 27, "07:30", "Breakfast halt"),
    ("Vagator", 15.5980, 73.7440, 27, "11:30", "Destination in Goa"),
    ("Dharwad", 15.4589, 75.0078, 29, "14:30", "Lunch halt"),
    ("Chitradurga", 14.2251, 76.3980, 29, "20:00", "Night halt"),
    ("Bengaluru", 12.9716, 77.5946, 30, "11:00", "Home"),
]

GROUP_NAME = "Goa ride Option 1"
LEADER = "Arjun"

# Pit stop 1 gets a name typed by the leader, to show renaming a stop.
RENAME = {1: "Breakfast at Benne Dose Hotel"}

# Riders who join the demo group from the recorder (they are not on the phone being filmed).
RIDERS = [
    {"name": "Ravi", "gear": {"d11": "First-aid kit"}, "offset": (-0.004, 0.003)},
    {"name": "Kiran", "gear": {"d9": "Toolkit", "d10": "Chain lube"}, "offset": (0.003, -0.004)},
    {"name": "Meera", "gear": {"d6": "Rain gear"}, "offset": (-0.002, -0.005)},
]


def google_maps_link() -> str:
    """A Google Maps directions link with every point named, like the one a leader copies from Google Maps."""
    names = "/".join(p[0].replace(" ", "+") for p in POINTS)
    data = "".join(f"!1m5!1m1!1s0x0:0x0!2m2!1d{lon:.4f}!2d{lat:.4f}" for _, lat, lon, *_ in POINTS)
    return f"https://www.google.com/maps/dir/{names}/@14.6,75.6,7z/data=!4m{len(POINTS) * 6 + 2}!4m{len(POINTS) * 6 + 1}{data}"


if __name__ == "__main__":
    print(google_maps_link())
