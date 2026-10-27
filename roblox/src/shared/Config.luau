local Config = {}

Config.ArenaSize = 360
Config.UltMax = 150
Config.IntermissionSeconds = 12
Config.MaxAlive = 24
Config.SpawnInterval = 0.8
Config.RespawnSeconds = 5

-- Optional: put rbxassetid:// sound ids here to get gun audio.
Config.Weapons = {
	Pistol = {
		Order = 1, Damage = 26, FireDelay = 0.22, Mag = 14, ReloadTime = 1.1, Pellets = 1, Spread = 0.25,
		Auto = false, Range = 500, Recoil = 1.4, HeadMult = 2, Length = 1.0,
		Color = Color3.fromRGB(60, 64, 78), Tracer = Color3.fromRGB(255, 225, 140),
	},
	Rifle = {
		Order = 2, Damage = 18, FireDelay = 0.09, Mag = 30, ReloadTime = 1.7, Pellets = 1, Spread = 0.9,
		Auto = true, Range = 500, Recoil = 0.9, HeadMult = 2, Length = 2.2,
		Color = Color3.fromRGB(45, 80, 60), Tracer = Color3.fromRGB(140, 255, 190),
	},
	Shotgun = {
		Order = 3, Damage = 14, FireDelay = 0.85, Mag = 6, ReloadTime = 2.0, Pellets = 8, Spread = 5,
		Auto = false, Range = 120, Recoil = 5, HeadMult = 1.5, Length = 2.0,
		Color = Color3.fromRGB(110, 70, 40), Tracer = Color3.fromRGB(255, 170, 90),
	},
	Sniper = {
		Order = 4, Damage = 120, FireDelay = 1.3, Mag = 5, ReloadTime = 2.4, Pellets = 1, Spread = 0,
		Auto = false, Range = 1000, Recoil = 6, HeadMult = 2.5, Length = 3.0,
		Color = Color3.fromRGB(40, 40, 55), Tracer = Color3.fromRGB(150, 200, 255),
	},
}

Config.Zombies = {
	Walker = {
		Health = 100, Speed = 9, Damage = 10, AttackDelay = 1.2, Range = 5, Scale = 1, Charge = 5,
		Skin = Color3.fromRGB(125, 158, 105), Shirt = Color3.fromRGB(70, 80, 115), Pants = Color3.fromRGB(50, 50, 60),
		Eye = Color3.fromRGB(190, 255, 90),
	},
	Runner = {
		Health = 60, Speed = 17, Damage = 7, AttackDelay = 0.8, Range = 5, Scale = 0.9, Charge = 6, Run = true,
		Skin = Color3.fromRGB(150, 175, 110), Shirt = Color3.fromRGB(150, 70, 60), Pants = Color3.fromRGB(60, 55, 50),
		Eye = Color3.fromRGB(255, 220, 80),
	},
	Brute = {
		Health = 450, Speed = 7, Damage = 25, AttackDelay = 1.6, Range = 7, Scale = 1.5, Charge = 15, Glow = true,
		Skin = Color3.fromRGB(105, 135, 110), Shirt = Color3.fromRGB(60, 60, 70), Pants = Color3.fromRGB(35, 35, 45),
		Eye = Color3.fromRGB(255, 140, 60),
	},
	Boss = {
		Name = "THE HOLLOW KING", Health = 3000, Speed = 10, Damage = 40, AttackDelay = 1.5, Range = 11, Scale = 2.6,
		Charge = 60, Glow = true,
		Skin = Color3.fromRGB(90, 70, 130), Shirt = Color3.fromRGB(40, 30, 70), Pants = Color3.fromRGB(25, 20, 45),
		Eye = Color3.fromRGB(190, 120, 255),
	},
}

-- Cutscene = total cutscene length (s); Hit = when the effect lands during it.
Config.PowerOrder = { "Nova", "Chrono", "Skyfall" }
Config.Powers = {
	Nova = { Name = "Radiant Nova", Key = "Z", Cost = 50, Cutscene = 3.2, Hit = 1.4, Radius = 45, Damage = 160, Heal = 40 },
	Chrono = { Name = "Chrono Break", Key = "X", Cost = 100, Cutscene = 4, Hit = 1.5, Duration = 8 },
	Skyfall = { Name = "Skyfall", Key = "C", Cost = 150, Cutscene = 5, Hit = 1.8, Meteors = 20, Radius = 16, Damage = 400 },
}

return Config
