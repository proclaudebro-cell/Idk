local Players = game:GetService("Players")
local ReplicatedStorage = game:GetService("ReplicatedStorage")
local PathfindingService = game:GetService("PathfindingService")
local TweenService = game:GetService("TweenService")
local Debris = game:GetService("Debris")
local Workspace = game:GetService("Workspace")

local Config = require(ReplicatedStorage.Shared.Config)
local Remotes = require(script.Parent.Remotes)

local WALK_ANIM = "rbxassetid://507777826" -- Roblox default R15 walk
local RUN_ANIM = "rbxassetid://507767714" -- Roblox default R15 run

local Zombies = {}
local alive = {}

local folder = Instance.new("Folder")
folder.Name = "Zombies"
folder.Parent = Workspace
Zombies.folder = folder
Zombies.onKilled = Instance.new("BindableEvent") -- (model, killer: Player?, typeName: string)

function Zombies.count()
	local n = 0
	for _ in alive do
		n += 1
	end
	return n
end

function Zombies.list()
	local list = {}
	for model, z in alive do
		table.insert(list, { model = model, root = z.root })
	end
	return list
end

function Zombies.isZombie(model)
	return alive[model] ~= nil
end

local function styleModel(model, def)
	for _, d in model:GetDescendants() do
		if d:IsA("BasePart") then
			d.Material = Enum.Material.SmoothPlastic
			local n = d.Name
			if string.find(n, "Torso") or string.find(n, "UpperArm") then
				d.Color = def.Shirt
			elseif string.find(n, "Leg") or string.find(n, "Foot") then
				d.Color = def.Pants
			else
				d.Color = def.Skin
			end
		elseif d:IsA("Motor6D") and (d.Name == "LeftShoulder" or d.Name == "RightShoulder") then
			-- Arms-out zombie pose. If the arms point the wrong way, flip the sign.
			d.C0 = d.C0 * CFrame.Angles(-math.rad(75), 0, 0)
		end
	end

	local head = model:FindFirstChild("Head")
	if not head then
		return
	end
	for _, side in { -1, 1 } do
		local eye = Instance.new("Part")
		eye.Size = Vector3.new(0.18, 0.12, 0.08) * def.Scale
		eye.Material = Enum.Material.Neon
		eye.Color = def.Eye
		eye.CanCollide = false
		eye.CanQuery = false
		eye.Massless = true
		eye.CFrame = head.CFrame * CFrame.new(side * 0.2 * def.Scale, 0.05 * def.Scale, -0.58 * def.Scale)
		local weld = Instance.new("WeldConstraint")
		weld.Part0 = head
		weld.Part1 = eye
		weld.Parent = eye
		eye.Parent = model
	end
	if def.Glow then
		local light = Instance.new("PointLight")
		light.Color = def.Eye
		light.Range = 9 * def.Scale
		light.Brightness = 1.2
		light.Parent = head
	end
end

local function addHealthBar(model, humanoid, def)
	local gui = Instance.new("BillboardGui")
	gui.Name = "HealthBar"
	gui.Adornee = model:FindFirstChild("Head")
	gui.Size = UDim2.new(4 * def.Scale, 0, 0.35, 0)
	gui.StudsOffset = Vector3.new(0, 2.4 * def.Scale, 0)
	gui.MaxDistance = 90
	local bg = Instance.new("Frame")
	bg.Size = UDim2.fromScale(1, 1)
	bg.BackgroundColor3 = Color3.fromRGB(20, 20, 28)
	bg.BorderSizePixel = 0
	bg.Parent = gui
	local fill = Instance.new("Frame")
	fill.Size = UDim2.fromScale(1, 1)
	fill.BackgroundColor3 = Color3.fromRGB(255, 175, 40)
	fill.BorderSizePixel = 0
	fill.Parent = bg
	gui.Parent = model
	humanoid.HealthChanged:Connect(function(h)
		fill.Size = UDim2.fromScale(math.clamp(h / humanoid.MaxHealth, 0, 1), 1)
	end)
end

function Zombies.spawn(typeName, position, healthMult)
	local def = Config.Zombies[typeName]
	local desc = Instance.new("HumanoidDescription")
	desc.HeightScale = def.Scale
	desc.WidthScale = def.Scale
	desc.DepthScale = def.Scale
	desc.HeadScale = def.Scale

	local ok, model = pcall(Players.CreateHumanoidModelFromDescription, Players, desc, Enum.HumanoidRigType.R15)
	if not ok then
		warn("Zombie rig failed:", model)
		return nil
	end

	model.Name = def.Name or typeName
	local humanoid = model:FindFirstChildOfClass("Humanoid")
	local root = model:FindFirstChild("HumanoidRootPart")
	humanoid.MaxHealth = def.Health * (healthMult or 1)
	humanoid.Health = humanoid.MaxHealth
	humanoid.WalkSpeed = def.Speed
	humanoid.DisplayDistanceType = Enum.HumanoidDisplayDistanceType.None
	humanoid.BreakJointsOnDeath = false

	styleModel(model, def)
	addHealthBar(model, humanoid, def)

	model:PivotTo(CFrame.new(position))
	model.Parent = folder
	root:SetNetworkOwner(nil)

	local animator = humanoid:FindFirstChildOfClass("Animator") or Instance.new("Animator", humanoid)
	local anim = Instance.new("Animation")
	anim.AnimationId = def.Run and RUN_ANIM or WALK_ANIM
	local track = animator:LoadAnimation(anim)
	track.Looped = true
	track:Play()
	track:AdjustSpeed(def.Speed / 12)

	alive[model] = {
		model = model, humanoid = humanoid, root = root, def = def, typeName = typeName, track = track,
		lastAttack = 0, nextRepath = 0, waypoints = nil, wpIndex = 1, pathing = false,
	}
	humanoid.Died:Connect(function()
		Zombies.kill(model)
	end)
	return model
end

function Zombies.kill(model)
	local z = alive[model]
	if not z then
		return
	end
	alive[model] = nil

	local killer = Players:GetPlayerByUserId(model:GetAttribute("Attacker") or 0)
	Zombies.onKilled:Fire(model, killer, z.typeName)

	-- No gore: the zombie freezes, glows and dissolves into sparkles.
	local fx = model:FindFirstChild("FreezeFx")
	if fx then
		fx:Destroy()
	end
	local bar = model:FindFirstChild("HealthBar")
	if bar then
		bar:Destroy()
	end
	z.track:Stop()
	z.root.Anchored = true

	local emitter = Instance.new("ParticleEmitter")
	emitter.Texture = "rbxasset://textures/particles/sparkles_main.dds"
	emitter.Color = ColorSequence.new(Color3.fromRGB(150, 255, 200))
	emitter.Lifetime = NumberRange.new(0.6, 1)
	emitter.Speed = NumberRange.new(6, 14)
	emitter.SpreadAngle = Vector2.new(180, 180)
	emitter.Rate = 0
	emitter.Size = NumberSequence.new(0.6, 0)
	emitter.LightEmission = 1
	emitter.Parent = z.root
	emitter:Emit(30)

	for _, d in model:GetDescendants() do
		if d:IsA("BasePart") then
			d.CanCollide = false
			TweenService:Create(d, TweenInfo.new(0.8), { Transparency = 1 }):Play()
		end
	end
	Debris:AddItem(model, 1.5)
end

function Zombies.damage(model, amount, attacker)
	local z = alive[model]
	if not z then
		return false
	end
	if model:GetAttribute("Frozen") then
		amount *= 1.5
	end
	if attacker then
		model:SetAttribute("Attacker", attacker.UserId)
	end
	z.humanoid:TakeDamage(amount)
	return z.humanoid.Health <= 0
end

function Zombies.setFrozen(model, frozen, withFx)
	local z = alive[model]
	if not z then
		return
	end
	model:SetAttribute("Frozen", frozen or nil)
	z.humanoid.WalkSpeed = frozen and 0 or z.def.Speed
	z.track:AdjustSpeed(frozen and 0 or z.def.Speed / 12)
	local existing = model:FindFirstChild("FreezeFx")
	if existing then
		existing:Destroy()
	end
	if frozen and withFx then
		local h = Instance.new("Highlight")
		h.Name = "FreezeFx"
		h.FillColor = Color3.fromRGB(150, 220, 255)
		h.FillTransparency = 0.35
		h.OutlineColor = Color3.new(1, 1, 1)
		h.Parent = model
	end
end

function Zombies.freezeAll(duration)
	for _, entry in Zombies.list() do
		local model = entry.model
		Zombies.setFrozen(model, true, true)
		task.delay(duration, function()
			Zombies.setFrozen(model, false)
		end)
	end
end

function Zombies.clear()
	for model in alive do
		alive[model] = nil
		model:Destroy()
	end
end

-- AI ----------------------------------------------------------------------

local function nearestTarget(pos)
	local bestRoot, bestHum, bestDist
	for _, p in Players:GetPlayers() do
		local c = p.Character
		local hrp = c and c:FindFirstChild("HumanoidRootPart")
		local hum = c and c:FindFirstChildOfClass("Humanoid")
		if hrp and hum and hum.Health > 0 then
			local d = (hrp.Position - pos).Magnitude
			if not bestDist or d < bestDist then
				bestRoot, bestHum, bestDist = hrp, hum, d
			end
		end
	end
	return bestRoot, bestHum, bestDist
end

local function repath(z, target)
	if z.pathing then
		return
	end
	z.pathing = true
	task.spawn(function()
		local path = PathfindingService:CreatePath({ AgentRadius = 2.5, AgentHeight = 5, AgentCanJump = true })
		local ok = pcall(path.ComputeAsync, path, z.root.Position, target)
		if ok and path.Status == Enum.PathStatus.Success then
			z.waypoints = path:GetWaypoints()
			z.wpIndex = 2
		else
			z.waypoints = nil
		end
		z.pathing = false
	end)
end

local losParams = RaycastParams.new()
losParams.FilterType = Enum.RaycastFilterType.Exclude

local function step(z, now)
	local humanoid, root, def = z.humanoid, z.root, z.def
	if z.model:GetAttribute("Frozen") then
		return
	end
	local tRoot, tHum, dist = nearestTarget(root.Position)
	if not tRoot then
		humanoid:MoveTo(root.Position)
		return
	end

	if dist <= def.Range then
		humanoid:MoveTo(tRoot.Position)
		if now - z.lastAttack >= def.AttackDelay then
			z.lastAttack = now
			tHum:TakeDamage(def.Damage)
			local player = Players:GetPlayerFromCharacter(tHum.Parent)
			if player then
				Remotes.Shake:FireClient(player, 0.6)
			end
		end
		return
	end

	losParams.FilterDescendantsInstances = { folder, tHum.Parent }
	local blocked = dist > 30 or Workspace:Raycast(root.Position, tRoot.Position - root.Position, losParams) ~= nil
	if not blocked then
		z.waypoints = nil
		humanoid:MoveTo(tRoot.Position)
		return
	end

	if now >= z.nextRepath then
		z.nextRepath = now + 1.2
		repath(z, tRoot.Position)
	end
	local wp = z.waypoints and z.waypoints[z.wpIndex]
	if wp then
		local flat = Vector3.new(wp.Position.X, root.Position.Y, wp.Position.Z)
		if (flat - root.Position).Magnitude < 4 then
			z.wpIndex += 1
			wp = z.waypoints[z.wpIndex]
		end
	end
	if wp then
		if wp.Action == Enum.PathWaypointAction.Jump then
			humanoid.Jump = true
		end
		humanoid:MoveTo(wp.Position)
	else
		humanoid:MoveTo(tRoot.Position)
	end
end

function Zombies.startAI()
	task.spawn(function()
		while true do
			task.wait(0.2)
			local now = os.clock()
			for _, z in alive do
				local ok, err = pcall(step, z, now)
				if not ok then
					warn(err)
				end
			end
		end
	end)
end

return Zombies
