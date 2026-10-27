local Lighting = game:GetService("Lighting")
local Workspace = game:GetService("Workspace")
local ReplicatedStorage = game:GetService("ReplicatedStorage")

local Config = require(ReplicatedStorage.Shared.Config)

local Environment = {}

local SIZE = Config.ArenaSize
local rng = Random.new(1337)
local spawnRng = Random.new()

local function part(props, parent)
	local p = Instance.new("Part")
	p.Anchored = true
	p.TopSurface = Enum.SurfaceType.Smooth
	p.BottomSurface = Enum.SurfaceType.Smooth
	for k, v in props do
		p[k] = v
	end
	p.Parent = parent
	return p
end

local function lightingChild(class, name, props)
	local old = Lighting:FindFirstChild(name)
	if old then
		old:Destroy()
	end
	local inst = Instance.new(class)
	inst.Name = name
	for k, v in props do
		inst[k] = v
	end
	inst.Parent = Lighting
	return inst
end

-- Lighting & post-processing ----------------------------------------------
-- Lighting.Technology = Future is set in default.project.json (scripts can't set it).

function Environment.setupLighting()
	Lighting.ClockTime = 18.3
	Lighting.Brightness = 2.4
	Lighting.ExposureCompensation = 0.25
	Lighting.GlobalShadows = true
	Lighting.ShadowSoftness = 0.15
	Lighting.EnvironmentDiffuseScale = 1
	Lighting.EnvironmentSpecularScale = 1
	Lighting.Ambient = Color3.fromRGB(20, 24, 40)
	Lighting.OutdoorAmbient = Color3.fromRGB(45, 55, 85)
	Lighting.ColorShift_Top = Color3.fromRGB(255, 170, 110)

	lightingChild("Sky", "NightSky", { StarCount = 4000, CelestialBodiesShown = true })
	lightingChild("Atmosphere", "Atmosphere", {
		Density = 0.36, Offset = 0.2, Color = Color3.fromRGB(170, 150, 190),
		Decay = Color3.fromRGB(90, 70, 110), Glare = 0.5, Haze = 1.6,
	})
	lightingChild("BloomEffect", "Bloom", { Intensity = 0.7, Size = 30, Threshold = 1.0 })
	lightingChild("ColorCorrectionEffect", "Grade", {
		Contrast = 0.15, Saturation = 0.12, TintColor = Color3.fromRGB(255, 240, 235),
	})
	lightingChild("SunRaysEffect", "SunRays", { Intensity = 0.12, Spread = 0.6 })
	lightingChild("DepthOfFieldEffect", "DepthOfField", {
		FarIntensity = 0.12, FocusDistance = 60, InFocusRadius = 55, NearIntensity = 0.08,
	})

	local terrain = Workspace.Terrain
	local clouds = terrain:FindFirstChildOfClass("Clouds") or Instance.new("Clouds")
	clouds.Cover = 0.55
	clouds.Density = 0.6
	clouds.Color = Color3.fromRGB(110, 100, 140)
	clouds.Parent = terrain
end

-- Map ---------------------------------------------------------------------

local function windows(cx, cz, w, d, h, parent)
	local lit = Color3.fromRGB(255, 214, 140)
	for floor = 1, math.floor(h / 8) do
		local y = floor * 8 - 3
		for x = -w / 2 + 5, w / 2 - 5, 7 do
			for _, s in { -1, 1 } do
				if rng:NextNumber() < 0.3 then
					part({
						Size = Vector3.new(3, 4, 0.3), Position = Vector3.new(cx + x, y, cz + s * d / 2),
						Material = Enum.Material.Neon, Color = lit, CanCollide = false,
					}, parent)
				end
			end
		end
		for z = -d / 2 + 5, d / 2 - 5, 7 do
			for _, s in { -1, 1 } do
				if rng:NextNumber() < 0.3 then
					part({
						Size = Vector3.new(0.3, 4, 3), Position = Vector3.new(cx + s * w / 2, y, cz + z),
						Material = Enum.Material.Neon, Color = lit, CanCollide = false,
					}, parent)
				end
			end
		end
	end
end

local function building(cx, cz, parent)
	local w, d = rng:NextInteger(38, 50), rng:NextInteger(38, 50)
	local h = rng:NextInteger(20, 60)
	local tone = rng:NextInteger(70, 120)
	local brick = rng:NextNumber() < 0.4
	part({
		Name = "Building", Size = Vector3.new(w, h, d), Position = Vector3.new(cx, h / 2, cz),
		Material = brick and Enum.Material.Brick or Enum.Material.Concrete,
		Color = brick and Color3.fromRGB(tone + 40, tone - 10, tone - 20) or Color3.fromRGB(tone, tone, tone + 8),
	}, parent)
	part({
		Name = "Roof", Size = Vector3.new(w + 1, 1.5, d + 1), Position = Vector3.new(cx, h + 0.75, cz),
		Material = Enum.Material.Slate, Color = Color3.fromRGB(45, 45, 52),
	}, parent)
	part({
		Name = "AC", Size = Vector3.new(6, 3, 6),
		Position = Vector3.new(cx + rng:NextInteger(-10, 10), h + 3, cz + rng:NextInteger(-10, 10)),
		Material = Enum.Material.DiamondPlate, Color = Color3.fromRGB(130, 135, 145),
	}, parent)
	windows(cx, cz, w, d, h, parent)
end

local function streetLamp(x, z, shadows, parent)
	part({ Size = Vector3.new(0.7, 16, 0.7), Position = Vector3.new(x, 8, z), Material = Enum.Material.Metal, Color = Color3.fromRGB(30, 30, 36) }, parent)
	local head = part({
		Size = Vector3.new(3, 0.6, 1.6), Position = Vector3.new(x, 16.3, z),
		Material = Enum.Material.Neon, Color = Color3.fromRGB(255, 214, 150), CanCollide = false,
	}, parent)
	local light = Instance.new("PointLight")
	light.Range = 45
	light.Brightness = 1.6
	light.Color = Color3.fromRGB(255, 205, 140)
	light.Shadows = shadows
	light.Parent = head
end

local function car(x, z, parent)
	local palette = {
		Color3.fromRGB(150, 40, 40), Color3.fromRGB(40, 70, 130),
		Color3.fromRGB(190, 190, 195), Color3.fromRGB(40, 110, 80),
	}
	local cf = CFrame.new(x, 1.9, z) * CFrame.Angles(0, rng:NextNumber(0, math.pi * 2), 0)
	local model = Instance.new("Model")
	model.Name = "Car"
	part({ Size = Vector3.new(6, 2, 13), CFrame = cf, Material = Enum.Material.Metal, Color = palette[rng:NextInteger(1, #palette)] }, model)
	part({
		Size = Vector3.new(5, 1.8, 6), CFrame = cf * CFrame.new(0, 1.9, -0.5),
		Material = Enum.Material.Glass, Color = Color3.fromRGB(30, 40, 55), Transparency = 0.3,
	}, model)
	for _, sx in { -3, 3 } do
		for _, sz in { -4, 4 } do
			part({
				Size = Vector3.new(1.2, 2.4, 2.4), CFrame = cf * CFrame.new(sx, -0.8, sz), Shape = Enum.PartType.Cylinder,
				Material = Enum.Material.Rubber, Color = Color3.fromRGB(20, 20, 22), CanCollide = false,
			}, model)
		end
	end
	model.Parent = parent
end

local function fog(x, z, parent)
	local p = part({
		Size = Vector3.new(10, 1, 10), Position = Vector3.new(x, 1.5, z),
		Transparency = 1, CanCollide = false, CanQuery = false,
	}, parent)
	local e = Instance.new("ParticleEmitter")
	e.Texture = "rbxasset://textures/particles/smoke_main.dds"
	e.Color = ColorSequence.new(Color3.fromRGB(150, 160, 190))
	e.Transparency = NumberSequence.new(0.88, 0.96)
	e.Size = NumberSequence.new(18, 30)
	e.Lifetime = NumberRange.new(10, 14)
	e.Rate = 1.5
	e.Speed = NumberRange.new(0.5, 1.5)
	e.Rotation = NumberRange.new(0, 360)
	e.Parent = p
end

local function monument(parent)
	part({
		Name = "MonumentBase", Shape = Enum.PartType.Cylinder, Size = Vector3.new(2, 20, 20),
		CFrame = CFrame.new(0, 1, 0) * CFrame.Angles(0, 0, math.pi / 2),
		Material = Enum.Material.Marble, Color = Color3.fromRGB(200, 200, 210),
	}, parent)
	part({
		Shape = Enum.PartType.Cylinder, Size = Vector3.new(0.4, 17, 17),
		CFrame = CFrame.new(0, 2.1, 0) * CFrame.Angles(0, 0, math.pi / 2),
		Material = Enum.Material.Neon, Color = Color3.fromRGB(90, 220, 255), CanCollide = false,
	}, parent)
	local crystal = part({
		Name = "Crystal", Size = Vector3.new(4, 12, 4), CFrame = CFrame.new(0, 10, 0) * CFrame.Angles(0, math.rad(45), 0),
		Material = Enum.Material.Neon, Color = Color3.fromRGB(120, 230, 255), CanCollide = false,
	}, parent)
	local light = Instance.new("PointLight")
	light.Range = 60
	light.Brightness = 3
	light.Color = Color3.fromRGB(120, 230, 255)
	light.Shadows = true
	light.Parent = crystal
end

function Environment.buildMap()
	local map = Instance.new("Folder")
	map.Name = "Map"

	part({
		Name = "Ground", Size = Vector3.new(SIZE, 2, SIZE), Position = Vector3.new(0, -1, 0),
		Material = Enum.Material.Asphalt, Color = Color3.fromRGB(48, 50, 56),
	}, map)

	local half = SIZE / 2
	for _, s in { -1, 1 } do
		part({ Name = "Wall", Size = Vector3.new(SIZE + 4, 26, 4), Position = Vector3.new(0, 13, s * half), Material = Enum.Material.Concrete, Color = Color3.fromRGB(70, 72, 80) }, map)
		part({ Name = "Wall", Size = Vector3.new(4, 26, SIZE + 4), Position = Vector3.new(s * half, 13, 0), Material = Enum.Material.Concrete, Color = Color3.fromRGB(70, 72, 80) }, map)
	end

	-- City lots; streets run through x/z = -80, 0, 80.
	for _, cx in { -120, -40, 40, 120 } do
		for _, cz in { -120, -40, 40, 120 } do
			if rng:NextNumber() < 0.75 then
				building(cx, cz, map)
			else
				for _ = 1, 4 do
					part({
						Name = "Crate", Size = Vector3.new(4, 4, 4),
						CFrame = CFrame.new(cx + rng:NextInteger(-18, 18), 2, cz + rng:NextInteger(-18, 18)) * CFrame.Angles(0, rng:NextNumber(0, 6), 0),
						Material = Enum.Material.WoodPlanks, Color = Color3.fromRGB(150, 110, 70),
					}, map)
				end
			end
		end
	end

	local streets = { -80, 0, 80 }
	for i, sx in streets do
		for j, sz in streets do
			streetLamp(sx + 13, sz + 13, (i + j) % 2 == 0, map)
		end
	end
	for _, p in { Vector3.new(165, 0, 0), Vector3.new(-165, 0, 0), Vector3.new(0, 0, 165), Vector3.new(0, 0, -165) } do
		streetLamp(p.X, p.Z, false, map)
	end

	for _ = 1, 14 do
		local c = streets[rng:NextInteger(1, 3)]
		local along, lateral = rng:NextInteger(-140, 140), c + rng:NextInteger(-6, 6)
		if rng:NextNumber() < 0.5 then
			car(lateral, along, map)
		else
			car(along, lateral, map)
		end
	end
	for _ = 1, 16 do
		local c = streets[rng:NextInteger(1, 3)]
		local along = rng:NextInteger(-140, 140)
		local onX = rng:NextNumber() < 0.5
		part({
			Name = "Barrier", Size = Vector3.new(8, 3.2, 2),
			CFrame = CFrame.new(onX and c or along, 1.6, onX and along or c) * CFrame.Angles(0, rng:NextNumber(0, math.pi), 0),
			Material = Enum.Material.Concrete, Color = Color3.fromRGB(150, 150, 155),
		}, map)
	end
	for _ = 1, 14 do
		fog(rng:NextInteger(-150, 150), rng:NextInteger(-150, 150), map)
	end

	monument(map)

	local spawn = Instance.new("SpawnLocation")
	spawn.Anchored = true
	spawn.Neutral = true
	spawn.Size = Vector3.new(14, 1, 14)
	spawn.Position = Vector3.new(0, 0.5, 24)
	spawn.Material = Enum.Material.Metal
	spawn.Color = Color3.fromRGB(60, 70, 90)
	spawn.Parent = map

	map.Parent = Workspace
end

function Environment.randomSpawn()
	local edge = SIZE / 2 - 18
	local a = spawnRng:NextNumber(-edge, edge)
	local side = spawnRng:NextInteger(1, 4)
	if side == 1 then
		return Vector3.new(a, 4, edge)
	elseif side == 2 then
		return Vector3.new(a, 4, -edge)
	elseif side == 3 then
		return Vector3.new(edge, 4, a)
	end
	return Vector3.new(-edge, 4, a)
end

return Environment
