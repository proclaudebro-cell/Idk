local Players = game:GetService("Players")
local ReplicatedStorage = game:GetService("ReplicatedStorage")
local TweenService = game:GetService("TweenService")
local Debris = game:GetService("Debris")
local Workspace = game:GetService("Workspace")

local Config = require(ReplicatedStorage.Shared.Config)
local Remotes = require(script.Parent.Remotes)
local Zombies = require(script.Parent.Zombies)

local Powers = {}
local busyUntil = 0
local rng = Random.new()

function Powers.addCharge(player, amount)
	local current = player:GetAttribute("Ult") or 0
	player:SetAttribute("Ult", math.min(Config.UltMax, current + amount))
end

local function fxPart(props)
	local p = Instance.new("Part")
	p.Anchored = true
	p.CanCollide = false
	p.CanQuery = false
	p.CanTouch = false
	for k, v in props do
		p[k] = v
	end
	p.Parent = Workspace
	return p
end

local effects = {}

function effects.Nova(player, def, origin)
	local ring = fxPart({
		Shape = Enum.PartType.Cylinder, Size = Vector3.new(0.6, 2, 2),
		CFrame = CFrame.new(origin - Vector3.new(0, 2.5, 0)) * CFrame.Angles(0, 0, math.pi / 2),
		Material = Enum.Material.Neon, Color = Color3.fromRGB(255, 225, 140),
	})
	TweenService:Create(ring, TweenInfo.new(0.7, Enum.EasingStyle.Quad, Enum.EasingDirection.Out), {
		Size = Vector3.new(0.6, def.Radius * 2, def.Radius * 2), Transparency = 1,
	}):Play()
	Debris:AddItem(ring, 0.8)

	for _, entry in Zombies.list() do
		local offset = entry.root.Position - origin
		if offset.Magnitude <= def.Radius then
			Zombies.damage(entry.model, def.Damage, player)
			local mass = entry.root.AssemblyMass
			entry.root:ApplyImpulse(offset.Unit * mass * 60 + Vector3.new(0, mass * 40, 0))
		end
	end
	for _, p in Players:GetPlayers() do
		local c = p.Character
		local hrp = c and c:FindFirstChild("HumanoidRootPart")
		local hum = c and c:FindFirstChildOfClass("Humanoid")
		if hrp and hum and hum.Health > 0 and (hrp.Position - origin).Magnitude <= def.Radius then
			hum.Health = math.min(hum.MaxHealth, hum.Health + def.Heal)
		end
	end
end

function effects.Chrono(_, def)
	Zombies.freezeAll(def.Duration)
end

local function explode(position, def, player)
	local boom = Instance.new("Explosion")
	boom.Position = position
	boom.BlastRadius = def.Radius
	boom.BlastPressure = 0
	boom.DestroyJointRadiusPercent = 0
	boom.Parent = Workspace
	for _, entry in Zombies.list() do
		local offset = entry.root.Position - position
		if offset.Magnitude <= def.Radius then
			Zombies.damage(entry.model, def.Damage, player)
			entry.root:ApplyImpulse(Vector3.new(0, entry.root.AssemblyMass * 35, 0))
		end
	end
	Remotes.Shake:FireAllClients(0.7)
end

local function meteor(target, def, player)
	local marker = fxPart({
		Shape = Enum.PartType.Cylinder, Size = Vector3.new(0.2, def.Radius * 2, def.Radius * 2),
		CFrame = CFrame.new(target.X, 0.2, target.Z) * CFrame.Angles(0, 0, math.pi / 2),
		Material = Enum.Material.Neon, Color = Color3.fromRGB(255, 120, 60), Transparency = 0.6,
	})
	Debris:AddItem(marker, 1.2)

	local rock = fxPart({
		Shape = Enum.PartType.Ball, Size = Vector3.new(8, 8, 8), Position = target + Vector3.new(30, 150, -20),
		Material = Enum.Material.Neon, Color = Color3.fromRGB(255, 140, 50),
	})
	local fire = Instance.new("Fire")
	fire.Size = 14
	fire.Heat = 10
	fire.Parent = rock
	local tween = TweenService:Create(rock, TweenInfo.new(1.1, Enum.EasingStyle.Quad, Enum.EasingDirection.In), {
		Position = target + Vector3.new(0, 3, 0),
	})
	tween.Completed:Once(function()
		rock:Destroy()
		explode(target, def, player)
	end)
	tween:Play()
end

function effects.Skyfall(player, def, origin)
	for _ = 1, def.Meteors do
		local list = Zombies.list()
		local target
		if #list > 0 then
			local p = list[rng:NextInteger(1, #list)].root.Position
			target = Vector3.new(p.X + rng:NextNumber(-6, 6), 0, p.Z + rng:NextNumber(-6, 6))
		else
			target = Vector3.new(origin.X + rng:NextNumber(-60, 60), 0, origin.Z + rng:NextNumber(-60, 60))
		end
		meteor(target, def, player)
		task.wait(0.15)
	end
end

Remotes.UsePower.OnServerEvent:Connect(function(player, id)
	if type(id) ~= "string" then
		return
	end
	local def = Config.Powers[id]
	local effect = effects[id]
	if not def or not effect or os.clock() < busyUntil then
		return
	end
	local char = player.Character
	local hrp = char and char:FindFirstChild("HumanoidRootPart")
	local hum = char and char:FindFirstChildOfClass("Humanoid")
	local ult = player:GetAttribute("Ult") or 0
	if not hrp or not hum or hum.Health <= 0 or ult < def.Cost then
		return
	end

	busyUntil = os.clock() + def.Cutscene + 0.5
	player:SetAttribute("Ult", ult - def.Cost)

	local origin = hrp.Position
	local shield = Instance.new("ForceField")
	shield.Visible = false
	shield.Parent = char
	Debris:AddItem(shield, def.Cutscene + 1)

	Remotes.Cutscene:FireAllClients(id, { Caster = player, Position = origin })
	task.delay(def.Hit, effect, player, def, origin)
end)

return Powers
